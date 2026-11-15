package com.traderbro.signalanalysis.ingestion;

import com.traderbro.signalanalysis.domain.AvailabilityStatus;
import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the DB entities ({@code telegram_messages} current row and its version row) from a
 * canonical {@link TelegramMessage} snapshot, and computes the stable SHA-256 content hash. The
 * hash covers the normalized content plus the fields that can change on an edit, so an author
 * returning to an earlier text after a later edit still produces a new version. The JSON is
 * emitted with a fixed insertion order, so the hash is stable regardless of field ordering.
 */
public class TelegramMessageSnapshotMapper {

    private final ObjectMapper json;

    public TelegramMessageSnapshotMapper(ObjectMapper json) {
        this.json = json;
    }

    public String contentHash(TelegramMessage m) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        TelegramMessageContent c = m.content();
        canonical.put("contentType", c.contentType());
        canonical.put("text", c.textContent());
        canonical.put("entities", parse(c.formattedEntities()));
        canonical.put("media", parse(c.mediaMetadata()));
        canonical.put("forward", parse(c.forwardMetadata()));
        canonical.put("authorSignature", m.authorSignature());
        canonical.put("senderType", m.senderType());
        canonical.put("senderId", m.senderId());
        canonical.put("replyTo", m.replyToMessageId());
        canonical.put("editedAt", m.editedAt() == null ? null : m.editedAt().toString());
        try {
            String canonicalJson = json.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash message snapshot", e);
        }
    }

    private Object parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return json.readValue(s, Object.class);
        } catch (Exception e) {
            return s;
        }
    }

    /** Build the current projection row for a brand-new message (version 1). */
    public com.traderbro.signalanalysis.domain.TelegramMessage toCurrent(
            TelegramMessage m, UUID channelId, String hash, Instant now) {
        return com.traderbro.signalanalysis.domain.TelegramMessage.builder()
                .id(UUID.randomUUID())
                .channelId(channelId)
                .telegramMessageId(m.messageId())
                .senderType(m.senderType())
                .senderId(m.senderId())
                .authorSignature(m.authorSignature())
                .sentAt(m.sentAt())
                .sourceEditedAt(m.editedAt())
                .replyToMessageId(m.replyToMessageId())
                .forwardMetadata(m.content().forwardMetadata())
                .contentType(m.content().contentType())
                .textContent(m.content().textContent())
                .formattedEntities(m.content().formattedEntities())
                .mediaMetadata(m.content().mediaMetadata())
                .contentHash(hash)
                .currentVersionNo(1)
                .availabilityStatus(AvailabilityStatus.ACTIVE)
                .firstObservedAt(now)
                .lastObservedAt(now)
                .permanentlyDeleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Build an append-only version row. */
    public com.traderbro.signalanalysis.domain.TelegramMessageVersion toVersion(
            com.traderbro.signalanalysis.domain.TelegramMessage current,
            com.traderbro.signalanalysis.domain.ChangeType changeType, Instant observedAt,
            UUID sourceEventId, int versionNo) {
        return com.traderbro.signalanalysis.domain.TelegramMessageVersion.builder()
                .id(UUID.randomUUID())
                .messageId(current.getId())
                .versionNo(versionNo)
                .changeType(changeType)
                .sourceEditedAt(current.getSourceEditedAt())
                .observedAt(observedAt)
                .contentType(current.getContentType())
                .textContent(current.getTextContent())
                .formattedEntities(current.getFormattedEntities())
                .mediaMetadata(current.getMediaMetadata())
                .forwardMetadata(current.getForwardMetadata())
                .contentHash(current.getContentHash())
                .sourceEventId(sourceEventId)
                .createdAt(observedAt)
                .build();
    }

    /** Refresh the mutable current projection with a newer snapshot (edit). */
    public void updateCurrent(com.traderbro.signalanalysis.domain.TelegramMessage cur,
                              TelegramMessage m, String hash, int versionNo, Instant now) {
        cur.setSenderType(m.senderType());
        cur.setSenderId(m.senderId());
        cur.setAuthorSignature(m.authorSignature());
        cur.setSentAt(m.sentAt());
        cur.setSourceEditedAt(m.editedAt());
        cur.setReplyToMessageId(m.replyToMessageId());
        cur.setForwardMetadata(m.content().forwardMetadata());
        cur.setContentType(m.content().contentType());
        cur.setTextContent(m.content().textContent());
        cur.setFormattedEntities(m.content().formattedEntities());
        cur.setMediaMetadata(m.content().mediaMetadata());
        cur.setContentHash(hash);
        cur.setCurrentVersionNo(versionNo);
        cur.setAvailabilityStatus(AvailabilityStatus.ACTIVE);
        cur.setLastObservedAt(now);
        cur.setUpdatedAt(now);
    }
}