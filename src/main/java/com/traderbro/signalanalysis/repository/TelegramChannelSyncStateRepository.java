package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.TelegramChannelSyncState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for {@code telegram_channel_sync_state}. */
@Repository
public class TelegramChannelSyncStateRepository {

    private static final String COLS = """
            channel_id, oldest_loaded_message_id, oldest_loaded_message_sent_at,
            newest_loaded_message_id, full_backfill_completed, last_history_request_message_id,
            last_live_event_at, last_tail_sync_at, last_successful_sync_at, last_error_code,
            last_error_message, sync_attempts, updated_at
            """;

    private static final String UPSERT = """
            INSERT INTO telegram_channel_sync_state
                (channel_id, oldest_loaded_message_id, oldest_loaded_message_sent_at,
                 newest_loaded_message_id, full_backfill_completed, last_history_request_message_id,
                 last_live_event_at, last_tail_sync_at, last_successful_sync_at, last_error_code,
                 last_error_message, sync_attempts, updated_at)
            VALUES (:channelId, :oldestMsgId, :oldestSentAt, :newestMsgId, :fullBackfill,
                    :lastReqMsgId, :lastLiveAt, :lastTailAt, :lastSuccessAt, :errCode,
                    :errMsg, :attempts, :updatedAt)
            ON CONFLICT (channel_id) DO UPDATE SET
                oldest_loaded_message_id = EXCLUDED.oldest_loaded_message_id,
                oldest_loaded_message_sent_at = EXCLUDED.oldest_loaded_message_sent_at,
                newest_loaded_message_id = EXCLUDED.newest_loaded_message_id,
                full_backfill_completed = EXCLUDED.full_backfill_completed,
                last_history_request_message_id = EXCLUDED.last_history_request_message_id,
                last_live_event_at = EXCLUDED.last_live_event_at,
                last_tail_sync_at = EXCLUDED.last_tail_sync_at,
                last_successful_sync_at = EXCLUDED.last_successful_sync_at,
                last_error_code = EXCLUDED.last_error_code,
                last_error_message = EXCLUDED.last_error_message,
                sync_attempts = EXCLUDED.sync_attempts,
                updated_at = EXCLUDED.updated_at
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final RowMapper<TelegramChannelSyncState> mapper = new StateMapper();

    public TelegramChannelSyncStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void upsert(TelegramChannelSyncState s) {
        named.update(UPSERT, new MapSqlParameterSource()
                .addValue("channelId", s.getChannelId())
                .addValue("oldestMsgId", s.getOldestLoadedMessageId())
                .addValue("oldestSentAt", ts(s.getOldestLoadedMessageSentAt()))
                .addValue("newestMsgId", s.getNewestLoadedMessageId())
                .addValue("fullBackfill", s.isFullBackfillCompleted())
                .addValue("lastReqMsgId", s.getLastHistoryRequestMessageId())
                .addValue("lastLiveAt", ts(s.getLastLiveEventAt()))
                .addValue("lastTailAt", ts(s.getLastTailSyncAt()))
                .addValue("lastSuccessAt", ts(s.getLastSuccessfulSyncAt()))
                .addValue("errCode", s.getLastErrorCode())
                .addValue("errMsg", s.getLastErrorMessage())
                .addValue("attempts", s.getSyncAttempts())
                .addValue("updatedAt", ts(s.getUpdatedAt())));
    }

    public Optional<TelegramChannelSyncState> findByChannelId(UUID channelId) {
        List<TelegramChannelSyncState> rows = jdbc.query(
                "SELECT " + COLS + " FROM telegram_channel_sync_state WHERE channel_id = ?",
                mapper, channelId);
        return rows.stream().findFirst();
    }

    /** Bulk fetch for a page of channels (avoids N+1 in the UI list). */
    public java.util.Map<UUID, TelegramChannelSyncState> findByChannelIds(
            java.util.Collection<UUID> channelIds) {
        if (channelIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<TelegramChannelSyncState> rows = named.query(
                "SELECT " + COLS + " FROM telegram_channel_sync_state WHERE channel_id IN (:ids)",
                new MapSqlParameterSource("ids", channelIds), mapper);
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                TelegramChannelSyncState::getChannelId, s -> s, (a, b) -> a));
    }

    private static Timestamp ts(java.time.Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static final class StateMapper implements RowMapper<TelegramChannelSyncState> {
        @Override
        public TelegramChannelSyncState mapRow(ResultSet rs, int i) throws SQLException {
            return TelegramChannelSyncState.builder()
                    .channelId(rs.getObject("channel_id", UUID.class))
                    .oldestLoadedMessageId((Long) rs.getObject("oldest_loaded_message_id"))
                    .oldestLoadedMessageSentAt(rs.getTimestamp("oldest_loaded_message_sent_at") == null
                            ? null : rs.getTimestamp("oldest_loaded_message_sent_at").toInstant())
                    .newestLoadedMessageId((Long) rs.getObject("newest_loaded_message_id"))
                    .fullBackfillCompleted(rs.getBoolean("full_backfill_completed"))
                    .lastHistoryRequestMessageId((Long) rs.getObject("last_history_request_message_id"))
                    .lastLiveEventAt(rs.getTimestamp("last_live_event_at") == null
                            ? null : rs.getTimestamp("last_live_event_at").toInstant())
                    .lastTailSyncAt(rs.getTimestamp("last_tail_sync_at") == null
                            ? null : rs.getTimestamp("last_tail_sync_at").toInstant())
                    .lastSuccessfulSyncAt(rs.getTimestamp("last_successful_sync_at") == null
                            ? null : rs.getTimestamp("last_successful_sync_at").toInstant())
                    .lastErrorCode(rs.getString("last_error_code"))
                    .lastErrorMessage(rs.getString("last_error_message"))
                    .syncAttempts(rs.getInt("sync_attempts"))
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}