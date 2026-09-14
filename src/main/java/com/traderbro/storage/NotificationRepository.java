package com.traderbro.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.notify.NotificationRecord;
import com.traderbro.notify.NotificationStatus;
import com.traderbro.notify.NotificationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Persistent outbound notification queue ({@code notifications} table). */
@Repository
public class NotificationRepository implements NotificationStore {

    private static final String COLS =
            "id, created_at, type, level, payload, status, attempts, sent_at, telegram_message_id, dedup_key";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<NotificationRecord> mapper;

    public NotificationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = new NotificationMapper();
    }

    @Override
    public long insert(NotificationRecord r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO notifications (created_at, type, level, payload, status, attempts, dedup_key)
                    VALUES (?, ?, ?, ?::jsonb, ?, 0, ?)
                    """, new String[]{"id"});
            ps.setTimestamp(1, java.sql.Timestamp.from(r.getCreatedAt()));
            ps.setString(2, r.getType().name());
            ps.setString(3, r.getLevel().name());
            ps.setString(4, toJson(r.getPayload()));
            ps.setString(5, NotificationStatus.PENDING.name());
            ps.setString(6, r.getDedupKey());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    @Override
    public void markSent(long id, String telegramMessageId, Instant sentAt) {
        jdbc.update("UPDATE notifications SET status='SENT', telegram_message_id=?, sent_at=? WHERE id=?",
                telegramMessageId, java.sql.Timestamp.from(sentAt), id);
    }

    @Override
    public void markFailed(long id) {
        jdbc.update("UPDATE notifications SET status='FAILED' WHERE id=?", id);
    }

    @Override
    public void incrementAttempt(long id) {
        jdbc.update("UPDATE notifications SET attempts = attempts + 1 WHERE id=?", id);
    }

    @Override
    public List<NotificationRecord> findPendingForRetry(int maxAttempts, int limit) {
        return jdbc.query("""
                SELECT """ + COLS + """
                 FROM notifications
                 WHERE status IN ('PENDING','FAILED') AND attempts < ?
                 ORDER BY created_at
                 LIMIT ?
                """, mapper, maxAttempts, limit);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize notification payload", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private final class NotificationMapper implements RowMapper<NotificationRecord> {
        @Override
        public NotificationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return NotificationRecord.builder()
                    .id(rs.getLong("id"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .type(NotificationType.valueOf(rs.getString("type")))
                    .level(NotificationLevel.valueOf(rs.getString("level")))
                    .payload(fromJson(rs.getString("payload")))
                    .status(NotificationStatus.valueOf(rs.getString("status")))
                    .attempts(rs.getInt("attempts"))
                    .sentAt(rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant())
                    .telegramMessageId(rs.getString("telegram_message_id"))
                    .dedupKey(rs.getString("dedup_key"))
                    .build();
        }
    }
}