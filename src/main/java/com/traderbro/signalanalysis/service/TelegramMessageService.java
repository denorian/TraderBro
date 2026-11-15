package com.traderbro.signalanalysis.service;

import com.traderbro.signalanalysis.domain.AvailabilityStatus;
import com.traderbro.signalanalysis.domain.ChangeType;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramMessage;
import com.traderbro.signalanalysis.ingestion.TelegramMessageSnapshotMapper;
import com.traderbro.signalanalysis.repository.TelegramMessageRepository;
import com.traderbro.signalanalysis.repository.TelegramMessageVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional, idempotent persistence of message snapshots. A single physical edit that arrives
 * as two TDLib updates collapses into one version because processing locks the current row and
 * only creates a version when the canonical content hash actually changes. Deletion never erases
 * stored content.
 */
@Service
@ConditionalOnProperty(name = "signal-analysis.ingestion.enabled", havingValue = "true")
public class TelegramMessageService {

    public enum ApplyResult {
        CREATED,
        UPDATED,
        NO_CHANGE,
        SKIPPED_DELETED
    }

    private final TelegramMessageRepository messages;
    private final TelegramMessageVersionRepository versions;
    private final TelegramMessageSnapshotMapper mapper;

    public TelegramMessageService(TelegramMessageRepository messages,
                                  TelegramMessageVersionRepository versions,
                                  TelegramMessageSnapshotMapper mapper) {
        this.messages = messages;
        this.versions = versions;
        this.mapper = mapper;
    }

    /** Idempotently apply a message snapshot. Row is locked to serialize concurrent edits. */
    @Transactional
    public ApplyResult apply(TelegramChannel channel,
                             com.traderbro.signalanalysis.telegram.TelegramMessage snapshot,
                             ChangeType changeType, Instant observedAt, UUID sourceEventId) {
        UUID channelId = channel.getId();
        long messageId = snapshot.messageId();
        Optional<TelegramMessage> locked = messages.findByChannelAndMessageIdForUpdate(
                channelId, messageId);
        // An edit after a permanent delete must not resurrect the message.
        if (locked.isPresent() && locked.get().isPermanentlyDeleted()) {
            return ApplyResult.SKIPPED_DELETED;
        }
        String hash = mapper.contentHash(snapshot);

        if (locked.isEmpty()) {
            TelegramMessage current = mapper.toCurrent(snapshot, channelId, hash, observedAt);
            // Insert the current projection first so the version's FK (message_id ->
            // telegram_messages.id) is satisfied.
            boolean inserted = messages.insertCurrent(current);
            if (inserted) {
                versions.insert(mapper.toVersion(current, changeType, observedAt, sourceEventId, 1));
                return ApplyResult.CREATED;
            }
            return ApplyResult.NO_CHANGE;
        }

        TelegramMessage current = locked.get();
        if (current.getContentHash() != null && current.getContentHash().equals(hash)) {
            // Canonical snapshot unchanged — just refresh the last-observed time.
            messages.touchLastObserved(channelId, messageId, observedAt);
            return ApplyResult.NO_CHANGE;
        }
        int versionNo = current.getCurrentVersionNo() + 1;
        mapper.updateCurrent(current, snapshot, hash, versionNo, observedAt);
        versions.insert(mapper.toVersion(current, changeType, observedAt, sourceEventId, versionNo));
        messages.updateFromVersion(current);
        return ApplyResult.UPDATED;
    }

    /**
     * Apply the delete matrix from {@code updateDeleteMessages}. {@code fromCache=true} means the
     * message was only evicted from the local TDLib cache and must not be marked deleted.
     */
    @Transactional
    public void applyDelete(TelegramChannel channel, long messageId, boolean permanent,
                            boolean fromCache, Instant observedAt) {
        if (fromCache) {
            return;
        }
        UUID channelId = channel.getId();
        Optional<TelegramMessage> locked = messages.findByChannelAndMessageIdForUpdate(
                channelId, messageId);
        if (locked.isEmpty()) {
            return;
        }
        TelegramMessage cur = locked.get();
        Instant now = Instant.now();
        if (permanent) {
            messages.markAvailability(channelId, messageId, AvailabilityStatus.DELETED,
                    true, observedAt, now);
        } else if (cur.getAvailabilityStatus() == AvailabilityStatus.ACTIVE) {
            // Not a confirmed permanent deletion: message became inaccessible, keep content.
            messages.markAvailability(channelId, messageId, AvailabilityStatus.INACCESSIBLE,
                    false, null, now);
        }
    }
}