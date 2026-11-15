package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.TelegramChannel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Paginated, filtered channel listing for the UI. One query; no N+1 (aggregates fetched in bulk). */
@Repository
public class TelegramChannelQueryRepository {

    private static final String BASE = """
            SELECT id, telegram_chat_id, telegram_supergroup_id, title, username, is_channel,
                   is_subscribed, is_archived, membership_status, monitoring_enabled,
                   monitoring_updated_at, first_seen_at, last_seen_at, subscription_synced_at,
                   created_at, updated_at
            FROM telegram_channels
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<TelegramChannel> mapper = new ChannelRowMapper();

    public TelegramChannelQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Page(List<TelegramChannel> items, long total) {
    }

    /** Query with safe, parametrized filters. {@code monitoringOnly} narrows monitored channels. */
    public Page findPage(String query, Boolean subscribed, Boolean monitoring, Boolean archived,
                         int page, int size, String sort, String direction) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND (title ILIKE ? OR username ILIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (subscribed != null) {
            where.append(" AND is_subscribed = ?");
            args.add(subscribed);
        }
        if (monitoring != null) {
            where.append(" AND monitoring_enabled = ?");
            args.add(monitoring);
        }
        if (archived != null) {
            where.append(" AND is_archived = ?");
            args.add(archived);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_channels" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;

        String orderCol = switch (sort == null ? "title" : sort) {
            case "title" -> "title";
            case "lastMessageAt" -> "last_seen_at";
            case "lastSyncAt" -> "subscription_synced_at";
            default -> "title";
        };
        String dir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
        String sql = BASE + where + " ORDER BY " + orderCol + " " + dir
                + " LIMIT ? OFFSET ?";
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) page * size);
        List<TelegramChannel> items = jdbc.query(sql, mapper, all.toArray());
        return new Page(items, totalCount);
    }

    private static final class ChannelRowMapper implements RowMapper<TelegramChannel> {
        @Override
        public TelegramChannel mapRow(ResultSet rs, int i) throws SQLException {
            return TelegramChannel.builder()
                    .id(rs.getObject("id", java.util.UUID.class))
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