package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.SyncRunStatus;
import com.traderbro.signalanalysis.domain.SyncRunType;
import com.traderbro.signalanalysis.domain.TelegramSyncRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Ledger of sync runs ({@code telegram_sync_runs}) for observability and resume after restart. */
@Repository
public class TelegramSyncRunRepository {

    private static final String INSERT = """
            INSERT INTO telegram_sync_runs
                (id, channel_id, run_type, status, requested_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String START = """
            UPDATE telegram_sync_runs SET status='RUNNING', started_at=? WHERE id=?
            """;

    private static final String COMPLETE = """
            UPDATE telegram_sync_runs SET status='COMPLETED', finished_at=?,
                cursor_message_id=?, loaded_count=?, created_count=?, edited_count=?
            WHERE id=?
            """;

    private static final String FAIL = """
            UPDATE telegram_sync_runs SET status='FAILED', finished_at=?, cursor_message_id=?,
                error_code=?, error_message=? WHERE id=?
            """;

    private static final String CANCEL = """
            UPDATE telegram_sync_runs SET status='CANCELLED', finished_at=? WHERE id=?
            """;

    private static final String CURSOR = """
            UPDATE telegram_sync_runs SET cursor_message_id=?, loaded_count=?,
                created_count=?, edited_count=? WHERE id=?
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<TelegramSyncRun> mapper = (rs, i) -> TelegramSyncRun.builder()
            .id(rs.getObject("id", UUID.class))
            .channelId((UUID) rs.getObject("channel_id"))
            .runType(SyncRunType.valueOf(rs.getString("run_type")))
            .status(SyncRunStatus.valueOf(rs.getString("status")))
            .requestedAt(rs.getTimestamp("requested_at").toInstant())
            .startedAt(ts(rs.getTimestamp("started_at")))
            .finishedAt(ts(rs.getTimestamp("finished_at")))
            .cursorMessageId((Long) rs.getObject("cursor_message_id"))
            .loadedCount(rs.getInt("loaded_count"))
            .createdCount(rs.getInt("created_count"))
            .editedCount(rs.getInt("edited_count"))
            .errorCode(rs.getString("error_code"))
            .errorMessage(rs.getString("error_message"))
            .build();

    public TelegramSyncRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TelegramSyncRun r) {
        jdbc.update(INSERT, r.getId(), r.getChannelId(), r.getRunType().name(),
                r.getStatus().name(), Timestamp.from(r.getRequestedAt()));
    }

    public void markStarted(UUID id, Instant at) {
        jdbc.update(START, Timestamp.from(at), id);
    }

    public void markCompleted(UUID id, Instant at, Long cursor, int loaded, int created, int edited) {
        jdbc.update(COMPLETE, Timestamp.from(at), cursor, loaded, created, edited, id);
    }

    public void markFailed(UUID id, Instant at, Long cursor, String code, String message) {
        jdbc.update(FAIL, Timestamp.from(at), cursor, code, sanitize(message), id);
    }

    public void markCancelled(UUID id, Instant at) {
        jdbc.update(CANCEL, Timestamp.from(at), id);
    }

    public void updateCursor(UUID id, Long cursor, int loaded, int created, int edited) {
        jdbc.update(CURSOR, cursor, loaded, created, edited, id);
    }

    public Optional<TelegramSyncRun> findRunning(UUID channelId, SyncRunType type) {
        List<TelegramSyncRun> rows = jdbc.query(
                "SELECT * FROM telegram_sync_runs WHERE channel_id = ? AND run_type = ? AND status = 'RUNNING' ORDER BY requested_at DESC LIMIT 1",
                mapper, channelId, type.name());
        return rows.stream().findFirst();
    }

    /** After restart, no run may remain RUNNING forever: cancel every interrupted run. */
    public int cancelAllInterrupted() {
        return jdbc.update(
                "UPDATE telegram_sync_runs SET status='CANCELLED', finished_at=? WHERE status='RUNNING'",
                Timestamp.from(Instant.now()));
    }

    public boolean hasActiveRun(UUID channelId, SyncRunType type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_sync_runs WHERE channel_id = ? AND run_type = ? AND status = 'RUNNING'",
                Integer.class, channelId, type.name());
        return n != null && n > 0;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String sanitize(String s) {
        if (s == null || s.length() <= 2000) {
            return s;
        }
        return s.substring(0, 2000);
    }
}