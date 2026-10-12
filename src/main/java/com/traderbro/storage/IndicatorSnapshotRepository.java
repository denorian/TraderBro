package com.traderbro.storage;

import com.traderbro.core.domain.IndicatorSnapshot;
import com.traderbro.core.domain.enums.CandleInterval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence for per-bar {@code indicator_snapshots}. Insertion is idempotent
 * (ON CONFLICT DO NOTHING on the composite key) so re-runs and catch-up jobs are safe.
 */
@Repository
public class IndicatorSnapshotRepository {

    private static final String COLS = "figi, interval, ts, indicator, values";

    private static final String UPSERT = """
            INSERT INTO indicator_snapshots (figi, interval, ts, indicator, values)
            VALUES (:figi, :interval, :ts, :indicator, CAST(:values AS jsonb))
            ON CONFLICT (figi, interval, ts, indicator) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;
    private final RowMapper<IndicatorSnapshot> mapper;

    public IndicatorSnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
        this.mapper = new SnapshotMapper();
    }

    /** Idempotently writes snapshots; rows already present are skipped. */
    public void upsertAll(List<IndicatorSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = snapshots.stream()
                .map(s -> new MapSqlParameterSource()
                        .addValue("figi", s.getFigi())
                        .addValue("interval", s.getInterval().name())
                        .addValue("ts", java.sql.Timestamp.from(s.getTs()))
                        .addValue("indicator", s.getIndicator().name())
                        .addValue("values", toJson(s.getValues())))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
    }

    /** Latest {@code limit} snapshots (both indicators) for a figi+interval, ascending by ts. */
    public List<IndicatorSnapshot> findLatest(String figi, CandleInterval interval, int limit) {
        String sql = "SELECT " + COLS + " FROM indicator_snapshots"
                + " WHERE figi = ? AND interval = ?"
                + " ORDER BY ts DESC LIMIT ?";
        List<IndicatorSnapshot> desc = jdbc.query(sql, mapper, figi, interval.name(), limit);
        java.util.Collections.reverse(desc);
        return desc;
    }

    /** Latest snapshot of one indicator for a figi+interval, or empty when none yet. */
    public java.util.Optional<IndicatorSnapshot> findLatest(String figi, CandleInterval interval,
                                                            IndicatorSnapshot.IndicatorKind kind) {
        String sql = "SELECT " + COLS + " FROM indicator_snapshots"
                + " WHERE figi = ? AND interval = ? AND indicator = ?"
                + " ORDER BY ts DESC LIMIT 1";
        List<IndicatorSnapshot> rows = jdbc.query(sql, mapper, figi, interval.name(), kind.name());
        return rows.stream().findFirst();
    }

    /** Latest snapshot (ALLIGATOR and AO) per figi for one interval — dashboard summary. */
    public Map<String, List<IndicatorSnapshot>> latestForAll(CandleInterval interval) {
        String sql = """
                SELECT DISTINCT ON (figi, indicator) figi, interval, ts, indicator, values
                FROM indicator_snapshots
                WHERE interval = ?
                ORDER BY figi, indicator, ts DESC
                """;
        List<IndicatorSnapshot> rows = jdbc.query(sql, mapper, interval.name());
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(IndicatorSnapshot::getFigi));
    }

    /** Timestamps that already carry a snapshot for the range — used for gap detection. */
    public Set<Instant> findSnapshotTs(String figi, CandleInterval interval, Instant from, Instant to) {
        String sql = """
                SELECT DISTINCT ts FROM indicator_snapshots
                WHERE figi = ? AND interval = ? AND ts >= ? AND ts <= ?
                """;
        List<Instant> ts = jdbc.query(sql, (rs, n) -> rs.getTimestamp("ts").toInstant(),
                figi, interval.name(), java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return Set.copyOf(ts);
    }

    /** Deletes snapshots older than {@code cutoff} (retention job). */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM indicator_snapshots WHERE ts < ?",
                java.sql.Timestamp.from(cutoff));
    }

    private String toJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize indicator snapshot", e);
        }
    }

    private final class SnapshotMapper implements RowMapper<IndicatorSnapshot> {
        @Override
        public IndicatorSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return IndicatorSnapshot.builder()
                    .figi(rs.getString("figi"))
                    .interval(CandleInterval.valueOf(rs.getString("interval")))
                    .ts(rs.getTimestamp("ts").toInstant())
                    .indicator(IndicatorSnapshot.IndicatorKind.valueOf(rs.getString("indicator")))
                    .values(readValues(rs.getString("values")))
                    .build();
        }

        private Map<String, Object> readValues(String json) {
            if (json == null) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return Map.of();
            }
        }
    }
}