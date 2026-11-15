package com.traderbro.signalanalysis.service;

import com.traderbro.signalanalysis.domain.SignalIngestionStatus;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramChannelSyncState;
import com.traderbro.signalanalysis.repository.TelegramChannelRepository;
import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User monitoring flag management and the effective per-channel ingestion status. Enabling
 * monitoring for a channel with no active subscription is rejected; enabling during
 * {@code AUTH_REQUIRED} is allowed and surfaces as {@code WAITING_AUTH}. The flag is a user intent
 * and is preserved when a subscription is lost.
 */
@Service
public class TelegramMonitoringService {

    private final TelegramChannelRepository channels;
    private final TelegramSessionManager session;

    public TelegramMonitoringService(TelegramChannelRepository channels,
                                     TelegramSessionManager session) {
        this.channels = channels;
        this.session = session;
    }

    @Transactional
    public Optional<TelegramChannel> setMonitoring(UUID id, boolean enabled) {
        Optional<TelegramChannel> existing = channels.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (enabled && !existing.get().isSubscribed()) {
            throw new UnsubscribedChannelException(id);
        }
        return channels.setMonitoring(id, enabled, Instant.now());
    }

    /**
     * Bulk monitoring toggle for a set of channels. When enabling, unsubscribed channels are
     * skipped (and reported) rather than failing the whole batch; unknown ids are ignored.
     */
    @Transactional
    public BulkResult setMonitoringBulk(java.util.List<UUID> ids, boolean enabled) {
        int applied = 0;
        int skippedUnsubscribed = 0;
        int notFound = 0;
        Instant now = Instant.now();
        for (UUID id : ids) {
            Optional<TelegramChannel> existing = channels.findById(id);
            if (existing.isEmpty()) {
                notFound++;
                continue;
            }
            if (enabled && !existing.get().isSubscribed()) {
                skippedUnsubscribed++;
                continue;
            }
            channels.setMonitoring(id, enabled, now);
            applied++;
        }
        return new BulkResult(applied, skippedUnsubscribed, notFound);
    }

    /** Outcome of a bulk monitoring operation. */
    public record BulkResult(int applied, int skippedUnsubscribed, int notFound) {
    }

    /** Derive the effective ingestion status from stored facets + auth state (read-only). */
    public SignalIngestionStatus computeStatus(TelegramChannel channel,
                                               TelegramChannelSyncState state) {
        if (!channel.isMonitoringEnabled()) {
            return SignalIngestionStatus.DISABLED;
        }
        TelegramAuthState auth = session.get();
        if (!auth.isReady()) {
            return SignalIngestionStatus.WAITING_AUTH;
        }
        if (!channel.isSubscribed()) {
            return SignalIngestionStatus.UNAVAILABLE;
        }
        if (state != null && state.isFullBackfillCompleted()) {
            return SignalIngestionStatus.ACTIVE;
        }
        if (state != null && state.getLastErrorCode() != null) {
            return SignalIngestionStatus.DEGRADED;
        }
        return SignalIngestionStatus.BACKFILL;
    }

    /** Raised when monitoring is enabled for a channel that is no longer subscribed (HTTP 409). */
    public static class UnsubscribedChannelException extends RuntimeException {
        private final UUID channelId;

        public UnsubscribedChannelException(UUID channelId) {
            super("Channel is not subscribed: " + channelId);
            this.channelId = channelId;
        }

        public UUID getChannelId() {
            return channelId;
        }
    }
}