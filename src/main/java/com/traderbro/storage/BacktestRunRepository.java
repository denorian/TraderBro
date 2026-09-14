package com.traderbro.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderbro.core.backtest.BacktestReport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists and reads backtest runs ({@code backtest_runs} table). */
@Repository
public class BacktestRunRepository {

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BacktestRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
    }

    public void insert(BacktestReport report) {
        named.update("""
                INSERT INTO backtest_runs (id, strategy_id, figi, interval, from_ts, to_ts,
                                           params, metrics, code_version, created_at, bars_count,
                                           instrument_type)
                VALUES (:id, :strategyId, :figi, :interval, :from, :to,
                        CAST(:params AS jsonb), CAST(:metrics AS jsonb), :codeVersion, :createdAt,
                        :barsCount, :instrumentType)
                """, new MapSqlParameterSource()
                .addValue("id", report.getId())
                .addValue("strategyId", report.getStrategyId())
                .addValue("figi", report.getFigi())
                .addValue("interval", report.getInterval().name())
                .addValue("from", java.sql.Timestamp.from(report.getFrom()))
                .addValue("to", java.sql.Timestamp.from(report.getTo()))
                .addValue("params", toJson(report.getParams()))
                .addValue("metrics", toJson(report.getMetrics()))
                .addValue("codeVersion", report.getCodeVersion())
                .addValue("createdAt", java.sql.Timestamp.from(report.getCreatedAt()))
                .addValue("barsCount", report.getBarsCount())
                .addValue("instrumentType", report.getInstrumentType().name()));
    }

    public Optional<BacktestReport> findById(String id) {
        List<BacktestReport> rows = jdbc.query("""
                SELECT id, strategy_id, figi, interval, from_ts, to_ts, params, metrics,
                       code_version, created_at, bars_count, instrument_type
                FROM backtest_runs WHERE id=?
                """, (rs, n) -> map(rs), id);
        return rows.stream().findFirst();
    }

    private BacktestReport map(ResultSet rs) throws SQLException {
        return BacktestReport.builder()
                .id(rs.getString("id"))
                .strategyId(rs.getString("strategy_id"))
                .figi(rs.getString("figi"))
                .interval(com.traderbro.core.domain.enums.CandleInterval.valueOf(rs.getString("interval")))
                .from(rs.getTimestamp("from_ts").toInstant())
                .to(rs.getTimestamp("to_ts").toInstant())
                .params(fromMap(rs.getString("params")))
                .metrics(fromMap(rs.getString("metrics")))
                .codeVersion(rs.getString("code_version"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .barsCount(rs.getLong("bars_count"))
                .instrumentType(com.traderbro.core.domain.enums.InstrumentType.valueOf(
                        rs.getString("instrument_type")))
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize backtest value", e);
        }
    }

    private java.util.Map<String, String> fromMap(String json) {
        if (json == null) {
            return java.util.Map.of();
        }
        try {
            var raw = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.Map<String, Object>>() {
            });
            java.util.Map<String, String> out = new java.util.HashMap<>();
            raw.forEach((k, v) -> out.put(k, String.valueOf(v)));
            return out;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }
}