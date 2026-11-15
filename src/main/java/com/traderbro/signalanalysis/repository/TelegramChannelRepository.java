package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.TelegramChannel;
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

/** Persistence for {@code telegram_channels}: metadata for all subscribed broadcast channels. */
@Repository
public class TelegramChannelRepository {

    private static final String COLS = """
            id, telegram_chat_id, telegram_supergroup_id, title, username, is_channel,
            is_subscribed, is_archived, membership_status, monitoring_enabled,
            monitoring_updated_at, first_seen_at, last_seen_at, subscription_synced_at,
            created_at, updated_at
            """;

    private static final String UPSERT = """
            INSERT INTO telegram_channels
                (id, telegram_chat_id, telegram_supergroup_id, title, username, is_channel,
                 is_subscribed, is_archived, membership_status, monitoring_enabled,
                 monitoring_updated_at, first_seen_at, last_seen_at, subscription_synced_at,
                 created_at, updated_at)
            VALUES (:id, :chatId, :supergroupId, :title, :username, :isChannel,
                    :subscribed, :archived, :membershipStatus, :monitoringEnabled,
                    :monitoringUpdatedAt, :firstSeenAt, :lastSeenAt, :subscriptionSyncedAt,
                    :createdAt, :updatedAt)
            ON CONFLICT (telegram_chat_id) DO UPDATE SET
                telegram_supergroup_id = EXCLUDED.telegram_supergroup_id,
                title = EXCLUDED.title,
                username = EXCLUDED.username,
                is_channel = EXCLUDED.is_channel,
                is_subscribed = EXCLUDED.is_subscribed,
                is_archived = EXCLUDED.is_archived,
                membership_status = EXCLUDED.membership_status,
                last_seen_at = EXCLUDED.last_seen_at,
                subscription_synced_at = EXCLUDED.subscription_synced_at,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String UNSUBS_SCAN = "SELECT " + COLS
            + " FROM telegram_channels"
            + " WHERE is_subscribed = TRUE AND monitoring_enabled = TRUE";

    private static final String BY_CHAT_ID = "SELECT " + COLS
            + " FROM telegram_channels WHERE telegram_chat_id = ?";

    private static final String BY_SUPERGROUP_ID = "SELECT " + COLS
            + " FROM telegram_channels WHERE telegram_supergroup_id = ?";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final RowMapper<TelegramChannel> mapper = new ChannelMapper();

    public TelegramChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public TelegramChannel upsert(TelegramChannel c) {
        named.update(UPSERT, new MapSqlParameterSource()
                .addValue("id", c.getId())
                .addValue("chatId", c.getTelegramChatId())
                .addValue("supergroupId", c.getTelegramSupergroupId())
                .addValue("title", c.getTitle())
                .addValue("username", c.getUsername())
                .addValue("isChannel", c.isChannel())
                .addValue("subscribed", c.isSubscribed())
                .addValue("archived", c.isArchived())
                .addValue("membershipStatus", c.getMembershipStatus())
                .addValue("monitoringEnabled", c.isMonitoringEnabled())
                .addValue("monitoringUpdatedAt", ts(c.getMonitoringUpdatedAt()))
                .addValue("firstSeenAt", ts(c.getFirstSeenAt()))
                .addValue("lastSeenAt", ts(c.getLastSeenAt()))
                .addValue("subscriptionSyncedAt", ts(c.getSubscriptionSyncedAt()))
                .addValue("createdAt", ts(c.getCreatedAt()))
                .addValue("updatedAt", ts(c.getUpdatedAt())));
        return c;
    }

    public Optional<TelegramChannel> findByChatId(long chatId) {
        List<TelegramChannel> rows = jdbc.query(BY_CHAT_ID, mapper, chatId);
        return rows.stream().findFirst();
    }

    public Optional<TelegramChannel> findBySupergroupId(long supergroupId) {
        List<TelegramChannel> rows = jdbc.query(BY_SUPERGROUP_ID, mapper, supergroupId);
        return rows.stream().findFirst();
    }

    public Optional<TelegramChannel> findById(UUID id) {
        List<TelegramChannel> rows = jdbc.query(
                "SELECT " + COLS + " FROM telegram_channels WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    public List<TelegramChannel> findMonitoredSubscribed() {
        return jdbc.query(UNSUBS_SCAN, mapper);
    }

    /** Set the user intent flag. Returns the updated channel, or empty if the id is unknown. */
    public Optional<TelegramChannel> setMonitoring(UUID id, boolean enabled, java.time.Instant now) {
        int n = jdbc.update(
                "UPDATE telegram_channels SET monitoring_enabled = ?, monitoring_updated_at = ?, updated_at = ? WHERE id = ?",
                enabled, Timestamp.from(now), Timestamp.from(now), id);
        return n == 0 ? Optional.empty() : findById(id);
    }

    private static Timestamp ts(java.time.Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static final class ChannelMapper implements RowMapper<TelegramChannel> {
        @Override
        public TelegramChannel mapRow(ResultSet rs, int i) throws SQLException {
            return TelegramChannel.builder()
                    .id(rs.getObject("id", UUID.class))
                    .telegramChatId(rs.getLong("telegram_chat_id"))
                    .telegramSupergroupId((Long) rs.getObject("telegram_supergroup_id"))
                    .title(rs.getString("title"))
                    .username(rs.getString("username"))
                    .channel(rs.getBoolean("is_channel"))
                    .subscribed(rs.getBoolean("is_subscribed"))
                    .archived(rs.getBoolean("is_archived"))
                    .membershipStatus(rs.getString("membership_status"))
                    .monitoringEnabled(rs.getBoolean("monitoring_enabled"))
                    .monitoringUpdatedAt(rs.getTimestamp("monitoring_updated_at") == null
                            ? null : rs.getTimestamp("monitoring_updated_at").toInstant())
                    .firstSeenAt(rs.getTimestamp("first_seen_at").toInstant())
                    .lastSeenAt(rs.getTimestamp("last_seen_at").toInstant())
                    .subscriptionSyncedAt(rs.getTimestamp("subscription_synced_at") == null
                            ? null : rs.getTimestamp("subscription_synced_at").toInstant())
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}