package com.traderbro.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.SignalVerdict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists computed signals ({@code signals} table) for full reproducibility. */
@Repository
public class SignalRepository {

    private static final String COLUMNS =
            "id, created_at, strategy_id, figi, ts, direction, price, indicator_snapshot, verdict, reason";

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int moneyScale;
    private final RowMapper<Signal> mapper;

    public SignalRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
        this.moneyScale = moneyScale;
        this.mapper = new SignalMapper();
    }

    public void insert(Signal s) {
        named.update("""
                INSERT INTO signals (id, created_at, strategy_id, figi, ts, direction, price,
                                     indicator_snapshot, verdict, reason)
                VALUES (:id, :createdAt, :strategyId, :figi, :ts, :direction, :price,
                        CAST(:snapshot AS jsonb), :verdict, :reason)
                """, new MapSqlParameterSource()
                .addValue("id", s.getId())
                .addValue("createdAt", java.sql.Timestamp.from(s.getCreatedAt()))
                .addValue("strategyId", s.getStrategyId())
                .addValue("figi", s.getFigi())
                .addValue("ts", java.sql.Timestamp.from(s.getTs()))
                .addValue("direction", s.getDirection().name())
                .addValue("price", s.getPrice())
                .addValue("snapshot", toJson(s.getIndicatorSnapshot()))
                .addValue("verdict", s.getVerdict().name())
                .addValue("reason", s.getReason()));
    }

    public List<Signal> findByRange(Instant from, Instant to) {
        String sql = "SELECT " + COLUMNS + " FROM signals WHERE ts >= ? AND ts <= ? ORDER BY ts";
        return jdbc.query(sql, mapper, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    /** Returns the latest {@code limit} signals ordered by time descending. */
    public List<Signal> findLatest(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM signals ORDER BY ts DESC LIMIT ?";
        return jdbc.query(sql, mapper, limit);
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot == null ? Map.of() : snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize signal snapshot", e);
        }
    }

    private final class SignalMapper implements RowMapper<Signal> {
        @Override
        public Signal mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Signal.builder()
                    .id(rs.getObject("id", UUID.class))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .strategyId(rs.getString("strategy_id"))
                    .figi(rs.getString("figi"))
                    .ts(rs.getTimestamp("ts").toInstant())
                    .direction(com.traderbro.core.domain.enums.Direction.valueOf(rs.getString("direction")))
                    .price(rs.getBigDecimal("price").setScale(moneyScale))
                    .indicatorSnapshot(readSnapshot(rs.getString("indicator_snapshot")))
                    .verdict(SignalVerdict.valueOf(rs.getString("verdict")))
                    .reason(rs.getString("reason"))
                    .build();
        }

        private Map<String, Object> readSnapshot(String json) {
            if (json == null) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
            } catch (Exception e) {
                return Map.of();
            }
        }
    }
}