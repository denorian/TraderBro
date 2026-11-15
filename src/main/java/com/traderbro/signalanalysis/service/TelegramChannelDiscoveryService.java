package com.traderbro.signalanalysis.service;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.domain.SyncRunStatus;
import com.traderbro.signalanalysis.domain.SyncRunType;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramSyncRun;
import com.traderbro.signalanalysis.ingestion.TelegramIngestEventWriter;
import com.traderbro.signalanalysis.repository.TelegramChannelRepository;
import com.traderbro.signalanalysis.repository.TelegramSyncRunRepository;
import com.traderbro.signalanalysis.telegram.ChatList;
import com.traderbro.signalanalysis.telegram.MembershipStatus;
import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import com.traderbro.signalanalysis.telegram.TelegramChat;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import com.traderbro.signalanalysis.telegram.TelegramSupergroup;
import com.traderbro.signalanalysis.telegram.TelegramUserClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Discovers the broadcast channels the personal account is subscribed to (Main + Archive) and
 * keeps their metadata up to date. Unsubscription never deletes a channel row: it only flips
 * {@code is_subscribed} (preserving {@code monitoring_enabled}). Only broadcast channels
 * ({@code isChannel}) are stored; ordinary groups, discussion groups, private and secret chats
 * are excluded.
 */
@Slf4j
public class TelegramChannelDiscoveryService {

    private final TelegramChannelRepository channels;
    private final TelegramSyncRunRepository syncRuns;
    private final TelegramUserClient client;
    private final TelegramSessionManager session;
    private final SignalAnalysisProperties.Ingestion.Discovery cfg;

    public TelegramChannelDiscoveryService(TelegramChannelRepository channels,
                                           TelegramSyncRunRepository syncRuns,
                                           TelegramUserClient client,
                                           TelegramSessionManager session,
                                           SignalAnalysisProperties props) {
        this.channels = channels;
        this.syncRuns = syncRuns;
        this.client = client;
        this.session = session;
        this.cfg = props.getIngestion().getDiscovery();
    }

    /** Full Main+Archive discovery. Returns the number of broadcast channels stored. */
    public int discoverAll() {
        if (!session.isReady()) {
            throw new IllegalStateException("AUTH_REQUIRED");
        }
        TelegramSyncRun run = TelegramSyncRun.builder()
                .id(UUID.randomUUID())
                .channelId(null)
                .runType(SyncRunType.CHANNEL_DISCOVERY)
                .status(SyncRunStatus.RUNNING)
                .requestedAt(Instant.now())
                .build();
        syncRuns.insert(run);
        syncRuns.markStarted(run.getId(), Instant.now());
        int stored = 0;
        try {
            for (ChatList list : List.of(ChatList.MAIN, ChatList.ARCHIVE)) {
                for (long chatId : loadAllChatIds(list)) {
                    TelegramChat chat = client.getChat(chatId).join();
                    if (!chat.isChannel()) {
                        continue;
                    }
                    upsertChannel(chat, Instant.now());
                    stored++;
                }
            }
            syncRuns.markCompleted(run.getId(), Instant.now(), null, stored, stored, 0);
            return stored;
        } catch (Exception e) {
            syncRuns.markFailed(run.getId(), Instant.now(), null, "DISCOVERY_FAILED",
                    sanitize(e));
            throw e;
        }
    }

    private List<Long> loadAllChatIds(ChatList list) {
        Set<Long> seen = new HashSet<>();
        long deadline = System.nanoTime() + cfg.getCompletionTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            // loadChats returns 404 when there are no more chats in the list — that is the normal
            // end of pagination, not an error. Any other failure propagates.
            try {
                client.loadChats(list, cfg.getPageSize()).join();
            } catch (java.util.concurrent.CompletionException e) {
                if (isNotFound(e)) {
                    break;
                }
                throw e;
            }
            List<Long> ids = client.getChatIds(list, cfg.getPageSize()).join();
            if (ids.isEmpty()) {
                break;
            }
            int before = seen.size();
            seen.addAll(ids);
            if (ids.size() < cfg.getPageSize() || seen.size() == before) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    private static boolean isNotFound(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String msg = cause.getMessage();
        return msg != null && msg.contains("404");
    }

    /** Upsert a channel preserving {@code id}, {@code monitoring_enabled} and {@code first_seen_at}. */
    public void upsertChannel(TelegramChat chat, Instant now) {
        Optional<TelegramChannel> existing = channels.findByChatId(chat.chatId());
        boolean subscribed = chat.membershipStatus().isActiveSubscription();
        TelegramChannel c = TelegramChannel.builder()
                .id(existing.map(TelegramChannel::getId).orElseGet(UUID::randomUUID))
                .telegramChatId(chat.chatId())
                .telegramSupergroupId(chat.supergroupId())
                .title(chat.title())
                .username(chat.username())
                .channel(chat.isChannel())
                .subscribed(subscribed)
                .archived(chat.archived())
                .membershipStatus(chat.membershipStatus().name())
                .monitoringEnabled(existing.map(TelegramChannel::isMonitoringEnabled).orElse(false))
                .firstSeenAt(existing.map(TelegramChannel::getFirstSeenAt).orElse(now))
                .lastSeenAt(now)
                .subscriptionSyncedAt(now)
                .createdAt(existing.map(TelegramChannel::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build();
        channels.upsert(c);
    }

    /** Apply a live metadata update ({@code NewChat} or {@code Supergroup}) without losing state. */
    public void applyMetadata(TelegramIngestEventWriter.MetadataEnvelope envelope, Instant now) {
        switch (envelope.kind()) {
            case "chat" -> applyChat(envelope.json(), now);
            case "supergroup" -> applySupergroup(envelope.json(), now);
            default -> log.debug("Unknown metadata kind {}", envelope.kind());
        }
    }

    private void applyChat(String json, Instant now) {
        TelegramChat chat = parseChat(json);
        Optional<TelegramChannel> existing = channels.findByChatId(chat.chatId());
        boolean subscribed = existing.map(TelegramChannel::isSubscribed).orElse(false);
        String membership = existing.map(TelegramChannel::getMembershipStatus)
                .orElse(MembershipStatus.UNKNOWN.name());
        // A live NewChat update carries no membership; keep the last known subscription state.
        if (chat.membershipStatus() != MembershipStatus.UNKNOWN) {
            subscribed = chat.membershipStatus().isActiveSubscription();
            membership = chat.membershipStatus().name();
        }
        TelegramChannel c = TelegramChannel.builder()
                .id(existing.map(TelegramChannel::getId).orElseGet(UUID::randomUUID))
                .telegramChatId(chat.chatId())
                .telegramSupergroupId(chat.supergroupId())
                .title(chat.title())
                .username(chat.username())
                .channel(chat.isChannel())
                .subscribed(subscribed)
                .archived(chat.archived())
                .membershipStatus(membership)
                .monitoringEnabled(existing.map(TelegramChannel::isMonitoringEnabled).orElse(false))
                .firstSeenAt(existing.map(TelegramChannel::getFirstSeenAt).orElse(now))
                .lastSeenAt(now)
                .createdAt(existing.map(TelegramChannel::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build();
        channels.upsert(c);
    }

    private void applySupergroup(String json, Instant now) {
        TelegramSupergroup sg = parseSupergroup(json);
        channels.findBySupergroupId(sg.id()).ifPresent(existing -> {
            boolean subscribed = sg.membershipStatus().isActiveSubscription();
            TelegramChannel c = TelegramChannel.builder()
                    .id(existing.getId())
                    .telegramChatId(existing.getTelegramChatId())
                    .telegramSupergroupId(existing.getTelegramSupergroupId())
                    .title(existing.getTitle())
                    .username(sg.username())
                    .channel(existing.isChannel())
                    .subscribed(subscribed)
                    .archived(existing.isArchived())
                    .membershipStatus(sg.membershipStatus().name())
                    .monitoringEnabled(existing.isMonitoringEnabled())
                    .firstSeenAt(existing.getFirstSeenAt())
                    .lastSeenAt(now)
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(now)
                    .build();
            channels.upsert(c);
        });
    }

    private TelegramChat parseChat(String json) {
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json, TelegramChat.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse chat metadata", e);
        }
    }

    private TelegramSupergroup parseSupergroup(String json) {
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(json, TelegramSupergroup.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse supergroup metadata", e);
        }
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}