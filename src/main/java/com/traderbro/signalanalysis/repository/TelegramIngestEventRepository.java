package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.IngestEventStatus;
import com.traderbro.signalanalysis.domain.TelegramIngestEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable inbox ({@code telegram_ingest_events}). At-least-once with idempotent insert. */
@Repository
public class TelegramIngestEventRepository {

    private static final String INSERT = """
            INSERT INTO telegram_ingest_events
                (id, event_type, telegram_chat_id, telegram_message_id, dedup_key, payload,
                 received_at, status, attempts, available_at)
            VALUES (:id, :eventType, :chatId, :messageId, :dedupKey, :payload::jsonb,
                    :receivedAt, :status, :attempts, :availableAt)
            ON CONFLICT (dedup_key) DO NOTHING
            """;

    /** Claim a batch, skipping rows locked by other workers; surviving restart. */
    private static final String CLAIM = """
            WITH batch AS (
                SELECT received_seq FROM telegram_ingest_events
                WHERE status IN ('NEW','RETRY') AND available_at <= :now
                ORDER BY received_seq
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE telegram_ingest_events e
            SET status = 'PROCESSING', locked_at = :now, locked_by = :locker,
                attempts = attempts + 1
            FROM batch WHERE e.received_seq = batch.received_seq
            RETURNING e.received_seq, e.id, e.event_type, e.telegram_chat_id,
                      e.telegram_message_id, e.dedup_key, e.payload, e.received_at,
                      e.status, e.attempts, e.available_at, e.locked_at, e.locked_by,
                      e.processed_at, e.last_error
            """;

    private static final String PROCESSED = """
            UPDATE telegram_ingest_events
            SET status = 'PROCESSED', processed_at = ?, locked_at = NULL, locked_by = NULL,
                last_error = NULL
            WHERE received_seq = ?
            """;

    private static final String RETRY = """
            UPDATE telegram_ingest_events
            SET status = 'RETRY', available_at = ?, last_error = ?, locked_at = NULL, locked_by = NULL
            WHERE received_seq = ?
            """;

    private static final String FAILED = """
            UPDATE telegram_ingest_events
            SET status = 'FAILED', last_error = ?, locked_at = NULL, locked_by = NULL
            WHERE received_seq = ?
            """;

    private static final String CLEANUP = """
            DELETE FROM telegram_ingest_events
            WHERE status IN ('PROCESSED','IGNORED') AND received_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public TelegramIngestEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Persist a new inbox event; returns true only if it was actually inserted (dedup). */
    public boolean insert(TelegramIngestEvent e) {
        int n = named.update(INSERT, new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("eventType", e.getEventType())
                .addValue("chatId", e.getTelegramChatId())
                .addValue("messageId", e.getTelegramMessageId())
                .addValue("dedupKey", e.getDedupKey())
                .addValue("payload", e.getPayload())
                .addValue("receivedAt", Timestamp.from(e.getReceivedAt()))
                .addValue("status", e.getStatus().name())
                .addValue("attempts", e.getAttempts())
                .addValue("availableAt", Timestamp.from(e.getAvailableAt())));
        return n > 0;
    }

    public List<TelegramIngestEvent> claimBatch(Instant now, String locker, int batchSize) {
        return named.query(CLAIM, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("locker", locker)
                .addValue("batchSize", batchSize), EVENT_MAPPER);
    }

    public void markProcessed(long receivedSeq, Instant at) {
        jdbc.update(PROCESSED, Timestamp.from(at), receivedSeq);
    }

    public void markRetry(long receivedSeq, Instant availableAt, String lastError) {
        jdbc.update(RETRY, Timestamp.from(availableAt), sanitize(lastError), receivedSeq);
    }

    public void markFailed(long receivedSeq, String lastError) {
        jdbc.update(FAILED, sanitize(lastError), receivedSeq);
    }

    /** Raw-event retention: removes only PROCESSED/IGNORED older than the cutoff. */
    public int cleanupBefore(Instant cutoff) {
        return jdbc.update(CLEANUP, Timestamp.from(cutoff));
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_ingest_events WHERE status IN ('NEW','RETRY')",
                Long.class);
        return n == null ? 0 : n;
    }

    public long failedCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_ingest_events WHERE status = 'FAILED'",
                Long.class);
        return n == null ? 0 : n;
    }

    private static String sanitize(String s) {
        if (s == null || s.length() <= 2000) {
            return s;
        }
        return s.substring(0, 2000);
    }

    private static final RowMapper<TelegramIngestEvent> EVENT_MAPPER = new RowMapper<>() {
        @Override
        public TelegramIngestEvent mapRow(ResultSet rs, int i) throws SQLException {
            return TelegramIngestEvent.builder()
                    .receivedSeq(rs.getLong("received_seq"))
                    .id(rs.getObject("id", UUID.class))
                    .eventType(rs.getString("event_type"))
                    .telegramChatId(rs.getLong("telegram_chat_id"))
                    .telegramMessageId((Long) rs.getObject("telegram_message_id"))
                    .dedupKey(rs.getString("dedup_key"))
                    .payload(rs.getString("payload"))
                    .receivedAt(rs.getTimestamp("received_at").toInstant())
                    .status(IngestEventStatus.valueOf(rs.getString("status")))
                    .attempts(rs.getInt("attempts"))
                    .availableAt(rs.getTimestamp("available_at").toInstant())
                    .lockedAt(rs.getTimestamp("locked_at") == null
                            ? null : rs.getTimestamp("locked_at").toInstant())
                    .lockedBy(rs.getString("locked_by"))
                    .processedAt(rs.getTimestamp("processed_at") == null
                            ? null : rs.getTimestamp("processed_at").toInstant())
                    .lastError(rs.getString("last_error"))
                    .build();
        }
    };
}