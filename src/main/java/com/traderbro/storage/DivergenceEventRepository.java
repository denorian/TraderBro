package com.traderbro.storage;

import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for AO "Twin Peaks" {@code divergence_events}. Idempotent by natural key. */
@Repository
public class DivergenceEventRepository {

    private static final String COLS = """
            id, figi, interval, ts, direction, degree, ao_peak1_ts, ao_peak1_value,
            ao_peak2_ts, ao_peak2_value, price_at_peak1, price_at_peak2, strength, status,
            confirmed_at, created_at
            """;

    private static final String UPSERT = """
            INSERT INTO divergence_events
                (id, figi, interval, ts, direction, degree, ao_peak1_ts, ao_peak1_value,
                 ao_peak2_ts, ao_peak2_value, price_at_peak1, price_at_peak2, strength, status,
                 confirmed_at, created_at)
            VALUES (:id, :figi, :interval, :ts, :direction, :degree, :aoPeak1Ts, :aoPeak1Value,
                    :aoPeak2Ts, :aoPeak2Value, :priceAtPeak1, :priceAtPeak2, :strength, :status,
                    :confirmedAt, :createdAt)
            ON CONFLICT (figi, interval, ao_peak2_ts, direction) DO UPDATE SET
                degree = EXCLUDED.degree,
                strength = EXCLUDED.strength,
                status = EXCLUDED.status,
                confirmed_at = EXCLUDED.confirmed_at,
                created_at = EXCLUDED.created_at
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final RowMapper<DivergenceEvent> mapper = new DivergenceMapper();

    public DivergenceEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void upsertAll(List<DivergenceEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.getId())
                        .addValue("figi", e.getFigi())
                        .addValue("interval", e.getInterval().name())
                        .addValue("ts", Timestamp.from(e.getTs()))
                        .addValue("direction", e.getDirection().name())
                        .addValue("degree", e.getDegree())
                        .addValue("aoPeak1Ts", Timestamp.from(e.getAoPeak1Ts()))
                        .addValue("aoPeak1Value", e.getAoPeak1Value())
                        .addValue("aoPeak2Ts", Timestamp.from(e.getAoPeak2Ts()))
                        .addValue("aoPeak2Value", e.getAoPeak2Value())
                        .addValue("priceAtPeak1", e.getPriceAtPeak1())
                        .addValue("priceAtPeak2", e.getPriceAtPeak2())
                        .addValue("strength", e.getStrength())
                        .addValue("status", e.getStatus().name())
                        .addValue("confirmedAt", e.getConfirmedAt() == null
                                ? null : Timestamp.from(e.getConfirmedAt()))
                        .addValue("createdAt", Timestamp.from(e.getCreatedAt())))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
    }

    public List<DivergenceEvent> find(String figi, CandleInterval interval, Instant from,
                                      Instant to, DivergenceEvent.Status status,
                                      int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM divergence_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (figi != null) {
            sql.append(" AND figi = ?");
            args.add(figi);
        }
        if (interval != null) {
            sql.append(" AND interval = ?");
            args.add(interval.name());
        }
        if (from != null) {
            sql.append(" AND ts >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND ts <= ?");
            args.add(Timestamp.from(to));
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        args.add(Math.min(limit, 500));
        args.add(Math.max(offset, 0));
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public List<DivergenceEvent> findLatest(String figi, CandleInterval interval, int limit) {
        return jdbc.query("SELECT " + COLS
                        + " FROM divergence_events WHERE figi = ? AND interval = ?"
                        + " ORDER BY ts DESC LIMIT ?",
                mapper, figi, interval.name(), Math.min(limit, 200));
    }

    /** Latest divergence per instrument for a timeframe (Dashboard). */
    public List<DivergenceEvent> latestForAll(CandleInterval interval) {
        return jdbc.query("SELECT DISTINCT ON (figi) " + COLS
                        + " FROM divergence_events WHERE interval = ? ORDER BY figi, ts DESC",
                mapper, interval.name());
    }

    public Map<String, DivergenceEvent> latestByFigi(CandleInterval interval) {
        Map<String, DivergenceEvent> out = new java.util.LinkedHashMap<>();
        for (DivergenceEvent e : latestForAll(interval)) {
            out.put(e.getFigi(), e);
        }
        return out;
    }

    public DivergenceEvent findById(UUID id) {
        List<DivergenceEvent> rows = jdbc.query("SELECT " + COLS
                + " FROM divergence_events WHERE id = ?", mapper, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final class DivergenceMapper implements RowMapper<DivergenceEvent> {
        @Override
        public DivergenceEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp confirmed = rs.getTimestamp("confirmed_at");
            return DivergenceEvent.builder()
                    .id(rs.getObject("id", UUID.class))
                    .figi(rs.getString("figi"))
                    .interval(CandleInterval.valueOf(rs.getString("interval")))
                    .ts(rs.getTimestamp("ts").toInstant())
                    .direction(DivergenceEvent.Direction.valueOf(rs.getString("direction")))
                    .degree(rs.getInt("degree"))
                    .aoPeak1Ts(rs.getTimestamp("ao_peak1_ts").toInstant())
                    .aoPeak1Value(rs.getDouble("ao_peak1_value"))
                    .aoPeak2Ts(rs.getTimestamp("ao_peak2_ts").toInstant())
                    .aoPeak2Value(rs.getDouble("ao_peak2_value"))
                    .priceAtPeak1(rs.getDouble("price_at_peak1"))
                    .priceAtPeak2(rs.getDouble("price_at_peak2"))
                    .strength(rs.getDouble("strength"))
                    .status(DivergenceEvent.Status.valueOf(rs.getString("status")))
                    .confirmedAt(confirmed == null ? null : confirmed.toInstant())
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}