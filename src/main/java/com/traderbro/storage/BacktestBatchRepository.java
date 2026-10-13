package com.traderbro.storage;

import com.traderbro.core.backtest.BacktestMetrics;
import com.traderbro.core.backtest.BacktestRequest;
import com.traderbro.core.backtest.BacktestRun;
import com.traderbro.core.backtest.EquityPoint;
import com.traderbro.core.backtest.PairResult;
import com.traderbro.core.backtest.SignalRecord;
import com.traderbro.core.backtest.TradeRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence for orchestrated batch backtest runs and their detail tables. The run header
 * lives in {@code backtest_runs} (with {@code strategy_id}/{@code figi} NULL); per-pair metrics,
 * trades, signals and equity live in dedicated tables indexed by {@code run_id}.
 */
@Repository
public class BacktestBatchRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;

    public BacktestBatchRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- run header
    public void insertRun(BacktestRun run) {
        named.update("""
                INSERT INTO backtest_runs (id, strategy_id, figi, interval, from_ts, to_ts,
                                           params, metrics, code_version, created_at, bars_count,
                                           instrument_type, status, request, progress_pct,
                                           error_message, started_at, finished_at)
                VALUES (:id, NULL, NULL, :interval, :from, :to, NULL, NULL, :codeVersion, :createdAt,
                        0, NULL, :status, CAST(:request AS jsonb), :progress, :errorMessage,
                        :startedAt, :finishedAt)
                """, new MapSqlParameterSource()
                .addValue("id", run.getId())
                .addValue("interval", run.getRequest().interval().name())
                .addValue("from", ts(run.getRequest().from()))
                .addValue("to", ts(run.getRequest().to()))
                .addValue("codeVersion", run.getCodeVersion())
                .addValue("createdAt", ts(run.getCreatedAt()))
                .addValue("status", run.getStatus())
                .addValue("request", toJson(run.getRequest()))
                .addValue("progress", run.getProgressPct())
                .addValue("errorMessage", run.getErrorMessage())
                .addValue("startedAt", nullableTs(run.getStartedAt()))
                .addValue("finishedAt", nullableTs(run.getFinishedAt())));
    }

    public void updateStatus(String id, String status, int progressPct, String errorMessage,
                             Instant startedAt, Instant finishedAt) {
        named.update("""
                UPDATE backtest_runs SET status=:status, progress_pct=:progress,
                       error_message=:error, started_at=:startedAt, finished_at=:finishedAt
                WHERE id=:id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("progress", progressPct)
                .addValue("error", errorMessage)
                .addValue("startedAt", nullableTs(startedAt))
                .addValue("finishedAt", nullableTs(finishedAt)));
    }

    public void updateProgress(String id, int progressPct) {
        named.update("UPDATE backtest_runs SET progress_pct=:p WHERE id=:id",
                new MapSqlParameterSource().addValue("p", progressPct).addValue("id", id));
    }

    public Optional<BacktestRun> findRun(String id) {
        List<BacktestRun> rows = jdbc.query("""
                SELECT id, status, request, progress_pct, error_message, code_version,
                       created_at, started_at, finished_at
                FROM backtest_runs WHERE id=?
                """, this::mapRunHeader, id);
        return rows.stream().findFirst();
    }

    /** Lists run headers, newest first, with optional status/date filters and pagination. */
    public List<BacktestRun> findRuns(String status, Instant from, Instant to, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, status, request, progress_pct, error_message, code_version,
                       created_at, started_at, finished_at
                FROM backtest_runs WHERE strategy_id IS NULL
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND created_at>=?");
            args.add(ts(from));
        }
        if (to != null) {
            sql.append(" AND created_at<=?");
            args.add(ts(to));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), this::mapRunHeader, args.toArray());
    }

    private BacktestRun mapRunHeader(ResultSet rs, int n) throws SQLException {
        return BacktestRun.builder()
                .id(rs.getString("id"))
                .status(rs.getString("status"))
                .request(fromJson(rs.getString("request"), BacktestRequest.class))
                .progressPct(rs.getInt("progress_pct"))
                .errorMessage(rs.getString("error_message"))
                .codeVersion(rs.getString("code_version"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .startedAt(rs.getTimestamp("started_at") == null ? null
                        : rs.getTimestamp("started_at").toInstant())
                .finishedAt(rs.getTimestamp("finished_at") == null ? null
                        : rs.getTimestamp("finished_at").toInstant())
                .build();
    }

    // ---------------------------------------------------------------- pair results
    public void insertPairResult(String runId, PairResult pair) {
        named.update("""
                INSERT INTO backtest_pair_results (run_id, strategy_id, figi, metrics, bars_count)
                VALUES (:runId, :strategyId, :figi, CAST(:metrics AS jsonb), :bars)
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("strategyId", pair.getStrategyId())
                .addValue("figi", pair.getFigi())
                .addValue("metrics", toJson(pair.getMetrics()))
                .addValue("bars", pair.getBarsCount()));
    }

    public void insertTrades(String runId, List<TradeRecord> trades) {
        if (trades.isEmpty()) {
            return;
        }
        named.batchUpdate("""
                INSERT INTO backtest_trades (run_id, strategy_id, figi, direction, entry_ts,
                        entry_price, exit_ts, exit_price, quantity, pnl, pnl_pct, exit_reason)
                VALUES (:runId, :strategyId, :figi, :direction, :entryTs, :entryPrice,
                        :exitTs, :exitPrice, :quantity, :pnl, :pnlPct, :exitReason)
                """, trades.stream().map(t -> new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("strategyId", t.strategyId())
                .addValue("figi", t.figi())
                .addValue("direction", t.direction().name())
                .addValue("entryTs", ts(t.entryTs()))
                .addValue("entryPrice", t.entryPrice())
                .addValue("exitTs", ts(t.exitTs()))
                .addValue("exitPrice", t.exitPrice())
                .addValue("quantity", t.quantity())
                .addValue("pnl", t.pnl())
                .addValue("pnlPct", t.pnlPct())
                .addValue("exitReason", t.exitReason())).toArray(MapSqlParameterSource[]::new));
    }

    public void insertSignals(String runId, List<SignalRecord> signals) {
        if (signals.isEmpty()) {
            return;
        }
        named.batchUpdate("""
                INSERT INTO backtest_signals (run_id, strategy_id, figi, ts, type, price,
                        verdict, reason)
                VALUES (:runId, :strategyId, :figi, :ts, :type, :price, :verdict, :reason)
                """, signals.stream().map(s -> new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("strategyId", s.strategyId())
                .addValue("figi", s.figi())
                .addValue("ts", ts(s.ts()))
                .addValue("type", s.type().name())
                .addValue("price", s.price())
                .addValue("verdict", s.verdict())
                .addValue("reason", s.reason())).toArray(MapSqlParameterSource[]::new));
    }

    public void insertEquity(String runId, String strategyId, String figi, List<EquityPoint> equity) {
        if (equity.isEmpty()) {
            return;
        }
        named.batchUpdate("""
                INSERT INTO backtest_equity (run_id, strategy_id, figi, ts, equity)
                VALUES (:runId, :strategyId, :figi, :ts, :equity)
                """, equity.stream().map(e -> new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("strategyId", strategyId)
                .addValue("figi", figi)
                .addValue("ts", ts(e.ts()))
                .addValue("equity", e.equity())).toArray(MapSqlParameterSource[]::new));
    }

    public List<PairResult> findPairResults(String runId) {
        return jdbc.query("""
                SELECT strategy_id, figi, metrics, bars_count FROM backtest_pair_results
                WHERE run_id=? ORDER BY strategy_id, figi
                """, (rs, n) -> PairResult.builder()
                .strategyId(rs.getString("strategy_id"))
                .figi(rs.getString("figi"))
                .metrics(fromJson(rs.getString("metrics"), BacktestMetrics.class))
                .barsCount(rs.getLong("bars_count"))
                .trades(List.of()).signals(List.of()).equity(List.of()).build(), runId);
    }

    public List<SignalRecord> findSignals(String runId, String figi, String strategyId,
                                          int limit, long offset) {
        String sql = """
                SELECT strategy_id, figi, ts, type, price, verdict, reason
                FROM backtest_signals WHERE run_id=?
                """;
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(runId);
        if (figi != null) {
            sql += " AND figi=?"; args.add(figi);
        }
        if (strategyId != null) {
            sql += " AND strategy_id=?"; args.add(strategyId);
        }
        sql += " ORDER BY ts LIMIT ? OFFSET ?";
        args.add(limit); args.add(offset);
        return jdbc.query(sql, this::mapSignal, args.toArray());
    }

    public List<TradeRecord> findTrades(String runId, String figi, String strategyId,
                                        int limit, long offset) {
        String sql = """
                SELECT strategy_id, figi, direction, entry_ts, entry_price, exit_ts, exit_price,
                       quantity, pnl, pnl_pct, exit_reason
                FROM backtest_trades WHERE run_id=?
                """;
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(runId);
        if (figi != null) {
            sql += " AND figi=?"; args.add(figi);
        }
        if (strategyId != null) {
            sql += " AND strategy_id=?"; args.add(strategyId);
        }
        sql += " ORDER BY entry_ts LIMIT ? OFFSET ?";
        args.add(limit); args.add(offset);
        return jdbc.query(sql, this::mapTrade, args.toArray());
    }

    public List<EquityPoint> findEquity(String runId, String strategyId, String figi) {
        return jdbc.query("""
                SELECT ts, equity FROM backtest_equity
                WHERE run_id=? AND strategy_id=? AND figi=? ORDER BY ts
                """, (rs, n) -> new EquityPoint(rs.getTimestamp("ts").toInstant(),
                rs.getDouble("equity")), runId, strategyId, figi);
    }

    private SignalRecord mapSignal(ResultSet rs, int n) throws SQLException {
        return SignalRecord.builder()
                .strategyId(rs.getString("strategy_id"))
                .figi(rs.getString("figi"))
                .ts(rs.getTimestamp("ts").toInstant())
                .type(SignalRecord.SignalType.valueOf(rs.getString("type")))
                .price(rs.getDouble("price"))
                .verdict(rs.getString("verdict"))
                .reason(rs.getString("reason"))
                .build();
    }

    private TradeRecord mapTrade(ResultSet rs, int n) throws SQLException {
        return TradeRecord.builder()
                .strategyId(rs.getString("strategy_id"))
                .figi(rs.getString("figi"))
                .direction(com.traderbro.core.domain.enums.Direction.valueOf(rs.getString("direction")))
                .entryTs(rs.getTimestamp("entry_ts").toInstant())
                .entryPrice(rs.getDouble("entry_price"))
                .exitTs(rs.getTimestamp("exit_ts").toInstant())
                .exitPrice(rs.getDouble("exit_price"))
                .quantity(rs.getDouble("quantity"))
                .pnl(rs.getDouble("pnl"))
                .pnlPct(rs.getDouble("pnl_pct"))
                .exitReason(rs.getString("exit_reason"))
                .build();
    }

    // ---------------------------------------------------------------- admin
    public int delete(String id) {
        for (String table : List.of("backtest_equity", "backtest_signals", "backtest_trades",
                "backtest_pair_results")) {
            named.update("DELETE FROM " + table + " WHERE run_id=:id",
                    new MapSqlParameterSource("id", id));
        }
        return named.update("DELETE FROM backtest_runs WHERE id=:id",
                new MapSqlParameterSource("id", id));
    }

    public int purgeOlderThan(Instant cutoff) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM backtest_runs WHERE finished_at IS NOT NULL AND finished_at < ?",
                String.class, ts(cutoff));
        int deleted = 0;
        for (String id : ids) {
            deleted += delete(id);
        }
        return deleted;
    }

    /** A pair result for the compare view, carrying its parent run id. */
    public record ComparePair(String runId, String strategyId, String figi, BacktestMetrics metrics) {
    }

    public List<ComparePair> findPairsForCompare(List<String> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", runIds);
        return named.query("""
                SELECT run_id, strategy_id, figi, metrics, bars_count FROM backtest_pair_results
                WHERE run_id IN (:ids)
                """, params, (rs, n) -> new ComparePair(
                rs.getString("run_id"),
                rs.getString("strategy_id"),
                rs.getString("figi"),
                fromJson(rs.getString("metrics"), BacktestMetrics.class)));
    }

    // ---------------------------------------------------------------- helpers
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize backtest value", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Timestamp nullableTs(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}