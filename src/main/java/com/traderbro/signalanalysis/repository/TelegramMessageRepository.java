package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.AvailabilityStatus;
import com.traderbro.signalanalysis.domain.TelegramMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for {@code telegram_messages} (current projection). Idempotent by natural key. */
@Repository
public class TelegramMessageRepository {

    private static final String COLS = """
            id, channel_id, telegram_message_id, sender_type, sender_id, author_signature,
            sent_at, source_edited_at, reply_to_message_id, forward_metadata, content_type,
            text_content, formatted_entities, media_metadata, content_hash, current_version_no,
            availability_status, first_observed_at, last_observed_at, deletion_observed_at,
            is_permanently_deleted, created_at, updated_at
            """;

    private static final String INSERT_CURRENT = """
            INSERT INTO telegram_messages
                (id, channel_id, telegram_message_id, sender_type, sender_id, author_signature,
                 sent_at, source_edited_at, reply_to_message_id, forward_metadata, content_type,
                 text_content, formatted_entities, media_metadata, content_hash, current_version_no,
                 availability_status, first_observed_at, last_observed_at, deletion_observed_at,
                 is_permanently_deleted, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (channel_id, telegram_message_id) DO NOTHING
            """;

    private static final String UPDATE_FROM_VERSION = """
            UPDATE telegram_messages SET
                sender_type = ?, sender_id = ?, author_signature = ?, sent_at = ?,
                source_edited_at = ?, reply_to_message_id = ?, forward_metadata = ?::jsonb,
                content_type = ?, text_content = ?, formatted_entities = ?::jsonb,
                media_metadata = ?::jsonb, content_hash = ?, current_version_no = ?,
                availability_status = 'ACTIVE', last_observed_at = ?, updated_at = ?
            WHERE channel_id = ? AND telegram_message_id = ?
            """;

    private static final String BY_NATURAL = "SELECT " + COLS
            + " FROM telegram_messages WHERE channel_id = ? AND telegram_message_id = ? FOR UPDATE";

    private static final String TOUCH = """
            UPDATE telegram_messages SET last_observed_at = ? WHERE channel_id = ? AND telegram_message_id = ?
            """;

    private static final String MARK_AVAIL = """
            UPDATE telegram_messages SET availability_status = ?, is_permanently_deleted = ?,
                deletion_observed_at = ?, updated_at = ?
            WHERE channel_id = ? AND telegram_message_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<TelegramMessage> mapper = new MessageMapper();

    public TelegramMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert the current projection if absent (new message). Returns whether it was inserted. */
    public boolean insertCurrent(TelegramMessage m) {
        int n = jdbc.update(INSERT_CURRENT,
                m.getId(), m.getChannelId(), m.getTelegramMessageId(),
                m.getSenderType(), m.getSenderId(), m.getAuthorSignature(),
                Timestamp.from(m.getSentAt()), ts(m.getSourceEditedAt()),
                m.getReplyToMessageId(), m.getForwardMetadata(), m.getContentType(),
                m.getTextContent(), m.getFormattedEntities(), m.getMediaMetadata(),
                m.getContentHash(), m.getCurrentVersionNo(),
                m.getAvailabilityStatus().name(),
                Timestamp.from(m.getFirstObservedAt()), Timestamp.from(m.getLastObservedAt()),
                ts(m.getDeletionObservedAt()), m.isPermanentlyDeleted(),
                Timestamp.from(m.getCreatedAt()), Timestamp.from(m.getUpdatedAt()));
        return n > 0;
    }

    public void updateFromVersion(TelegramMessage m) {
        jdbc.update(UPDATE_FROM_VERSION,
                m.getSenderType(), m.getSenderId(), m.getAuthorSignature(),
                Timestamp.from(m.getSentAt()), ts(m.getSourceEditedAt()),
                m.getReplyToMessageId(), m.getForwardMetadata(), m.getContentType(),
                m.getTextContent(), m.getFormattedEntities(), m.getMediaMetadata(),
                m.getContentHash(), m.getCurrentVersionNo(),
                Timestamp.from(m.getLastObservedAt()), Timestamp.from(m.getUpdatedAt()),
                m.getChannelId(), m.getTelegramMessageId());
    }

    /** Load and lock the current row for update (edit serialization per natural key). */
    public Optional<TelegramMessage> findByChannelAndMessageIdForUpdate(UUID channelId, long messageId) {
        List<TelegramMessage> rows = jdbc.query(BY_NATURAL, mapper, channelId, messageId);
        return rows.stream().findFirst();
    }

    public void touchLastObserved(UUID channelId, long messageId, java.time.Instant at) {
        jdbc.update(TOUCH, Timestamp.from(at), channelId, messageId);
    }

    public boolean exists(UUID channelId, long messageId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_messages WHERE channel_id = ? AND telegram_message_id = ?",
                Integer.class, channelId, messageId);
        return n != null && n > 0;
    }

    public void markAvailability(UUID channelId, long messageId, AvailabilityStatus status,
                                 boolean permanentlyDeleted, java.time.Instant deletionObservedAt,
                                 java.time.Instant now) {
        jdbc.update(MARK_AVAIL, status.name(), permanentlyDeleted, ts(deletionObservedAt),
                Timestamp.from(now), channelId, messageId);
    }

    /** Aggregated counts per channel for the UI list, in a single query (no N+1). */
    public Map<UUID, ChannelCounts> countsByChannel() {
        List<Map.Entry<UUID, ChannelCounts>> rows = jdbc.query(
                """
                SELECT channel_id,
                       COUNT(*)                                 AS message_count,
                       COUNT(*) FILTER (WHERE current_version_no > 1) AS edited_count,
                       COUNT(*) FILTER (WHERE is_permanently_deleted) AS deleted_count
                FROM telegram_messages
                GROUP BY channel_id
                """,
                (rs, i) -> Map.entry(rs.getObject("channel_id", UUID.class),
                        new ChannelCounts(rs.getLong("message_count"),
                                rs.getLong("edited_count"), rs.getLong("deleted_count"))));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** When was the last message of a channel sent (for the UI list), per channel. */
    public Map<UUID, java.time.Instant> lastMessageAtByChannel() {
        List<Map.Entry<UUID, java.time.Instant>> rows = jdbc.query(
                "SELECT channel_id, MAX(sent_at) AS last FROM telegram_messages GROUP BY channel_id",
                (rs, i) -> Map.entry(rs.getObject("channel_id", UUID.class),
                        rs.getTimestamp("last").toInstant()));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Timestamp ts(java.time.Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    /** Lightweight per-channel aggregate for the channel list endpoint. */
    public record ChannelCounts(long messageCount, long editedCount, long deletedCount) {
    }

    private static final class MessageMapper implements RowMapper<TelegramMessage> {
        @Override
        public TelegramMessage mapRow(ResultSet rs, int i) throws SQLException {
            return TelegramMessage.builder()
                    .id(rs.getObject("id", UUID.class))
                    .channelId(rs.getObject("channel_id", UUID.class))
                    .telegramMessageId(rs.getLong("telegram_message_id"))
                    .senderType(rs.getString("sender_type"))
                    .senderId((Long) rs.getObject("sender_id"))
                    .authorSignature(rs.getString("author_signature"))
                    .sentAt(rs.getTimestamp("sent_at").toInstant())
                    .sourceEditedAt(rs.getTimestamp("source_edited_at") == null
                            ? null : rs.getTimestamp("source_edited_at").toInstant())
                    .replyToMessageId((Long) rs.getObject("reply_to_message_id"))
                    .forwardMetadata(rs.getString("forward_metadata"))
                    .contentType(rs.getString("content_type"))
                    .textContent(rs.getString("text_content"))
                    .formattedEntities(rs.getString("formatted_entities"))
                    .mediaMetadata(rs.getString("media_metadata"))
                    .contentHash(rs.getString("content_hash"))
                    .currentVersionNo(rs.getInt("current_version_no"))
                    .availabilityStatus(AvailabilityStatus.valueOf(rs.getString("availability_status")))
                    .firstObservedAt(rs.getTimestamp("first_observed_at").toInstant())
                    .lastObservedAt(rs.getTimestamp("last_observed_at").toInstant())
                    .deletionObservedAt(rs.getTimestamp("deletion_observed_at") == null
                            ? null : rs.getTimestamp("deletion_observed_at").toInstant())
                    .permanentlyDeleted(rs.getBoolean("is_permanently_deleted"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}