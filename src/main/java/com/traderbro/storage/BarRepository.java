package com.traderbro.storage;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent bar persistence against the {@code bars} hypertable. */
@Repository
public class BarRepository implements BarStore {

    private static final String UPSERT_SQL = """
            INSERT INTO bars (figi, interval, ts, open, high, low, close, volume, is_final)
            VALUES (:figi, :interval, :ts, :open, :high, :low, :close, :volume, :isFinal)
            ON CONFLICT (figi, interval, ts) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final int moneyScale;
    private final RowMapper<Bar> mapper;

    public BarRepository(JdbcTemplate jdbc, int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.moneyScale = moneyScale;
        this.mapper = new BarMapper();
    }

    @Override
    public void upsert(List<Bar> bars) {
        MapSqlParameterSource[] batch = bars.stream()
                .map(b -> new MapSqlParameterSource()
                        .addValue("figi", b.getFigi())
                        .addValue("interval", b.getInterval().name())
                        .addValue("ts", java.sql.Timestamp.from(b.getTs()))
                        .addValue("open", b.getOpen())
                        .addValue("high", b.getHigh())
                        .addValue("low", b.getLow())
                        .addValue("close", b.getClose())
                        .addValue("volume", b.getVolume())
                        .addValue("isFinal", b.isFinal_()))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT_SQL, batch);
    }

    @Override
    public List<Bar> findByFigiAndInterval(String figi, CandleInterval interval,
                                           Instant from, Instant to) {
        String sql = """
                SELECT figi, interval, ts, open, high, low, close, volume, is_final
                FROM bars
                WHERE figi = ? AND interval = ? AND ts >= ? AND ts <= ?
                ORDER BY ts
                """;
        return jdbc.query(sql, mapper, figi, interval.name(),
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    @Override
    public boolean exists(String figi, CandleInterval interval, Instant ts) {
        String sql = "SELECT EXISTS(SELECT 1 FROM bars WHERE figi = ? AND interval = ? AND ts = ?)";
        Boolean exists = jdbc.queryForObject(sql, Boolean.class, figi, interval.name(),
                java.sql.Timestamp.from(ts));
        return Boolean.TRUE.equals(exists);
    }

    private final class BarMapper implements RowMapper<Bar> {
        @Override
        public Bar mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Bar.builder()
                    .figi(rs.getString("figi"))
                    .interval(CandleInterval.valueOf(rs.getString("interval")))
                    .ts(rs.getTimestamp("ts").toInstant())
                    .open(rs.getBigDecimal("open").setScale(moneyScale))
                    .high(rs.getBigDecimal("high").setScale(moneyScale))
                    .low(rs.getBigDecimal("low").setScale(moneyScale))
                    .close(rs.getBigDecimal("close").setScale(moneyScale))
                    .volume(rs.getLong("volume"))
                    .final_(rs.getBoolean("is_final"))
                    .build();
        }
    }
}