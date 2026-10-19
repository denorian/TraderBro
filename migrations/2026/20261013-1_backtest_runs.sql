--liquibase formatted sql

--changeset a.s.brovko:20261013-1
--comment Stage 3.2 — orchestrated batch backtests: extend backtest_runs and add detail tables.
ALTER TABLE backtest_runs ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE backtest_runs ADD COLUMN request JSONB;
ALTER TABLE backtest_runs ADD COLUMN progress_pct INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_runs ADD COLUMN error_message TEXT;
ALTER TABLE backtest_runs ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE backtest_runs ADD COLUMN finished_at TIMESTAMPTZ;
-- A batch run aggregates many strategy×instrument pairs, so the single pair columns are optional.
ALTER TABLE backtest_runs ALTER COLUMN strategy_id DROP NOT NULL;
ALTER TABLE backtest_runs ALTER COLUMN figi DROP NOT NULL;
--rollback
--ALTER TABLE backtest_runs ALTER COLUMN figi SET NOT NULL;
--ALTER TABLE backtest_runs ALTER COLUMN strategy_id SET NOT NULL;
--ALTER TABLE backtest_runs DROP COLUMN finished_at;
--ALTER TABLE backtest_runs DROP COLUMN started_at;
--ALTER TABLE backtest_runs DROP COLUMN error_message;
--ALTER TABLE backtest_runs DROP COLUMN progress_pct;
--ALTER TABLE backtest_runs DROP COLUMN request;
--ALTER TABLE backtest_runs DROP COLUMN status;

--changeset a.s.brovko:20261013-2
--comment Per-pair metrics for a batch backtest run.
CREATE TABLE backtest_pair_results (
    run_id      VARCHAR(64)   NOT NULL,
    strategy_id VARCHAR(64)   NOT NULL,
    figi        VARCHAR(32)   NOT NULL,
    metrics     JSONB         NOT NULL,
    bars_count  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_bt_pair_results PRIMARY KEY (run_id, strategy_id, figi)
);
CREATE INDEX idx_bt_pair_run ON backtest_pair_results (run_id);
--rollback DROP TABLE backtest_pair_results;

--changeset a.s.brovko:20261013-3
--comment Closed trades of a batch backtest (for UI detail + chart pins).
CREATE TABLE backtest_trades (
    id          BIGSERIAL     NOT NULL PRIMARY KEY,
    run_id      VARCHAR(64)   NOT NULL,
    strategy_id VARCHAR(64)   NOT NULL,
    figi        VARCHAR(32)   NOT NULL,
    direction   VARCHAR(8)    NOT NULL,
    entry_ts    TIMESTAMPTZ   NOT NULL,
    entry_price NUMERIC(19,4) NOT NULL,
    exit_ts     TIMESTAMPTZ   NOT NULL,
    exit_price  NUMERIC(19,4) NOT NULL,
    quantity    NUMERIC(19,4) NOT NULL,
    pnl         NUMERIC(19,4) NOT NULL,
    pnl_pct     NUMERIC(19,4) NOT NULL,
    exit_reason VARCHAR(64)   NOT NULL
);
CREATE INDEX idx_bt_trades_run ON backtest_trades (run_id, figi, strategy_id);
--rollback DROP TABLE backtest_trades;

--changeset a.s.brovko:20261013-4
--comment ENTRY/EXIT signal points of a batch backtest (for chart pins).
CREATE TABLE backtest_signals (
    id          BIGSERIAL     NOT NULL PRIMARY KEY,
    run_id      VARCHAR(64)   NOT NULL,
    strategy_id VARCHAR(64)   NOT NULL,
    figi        VARCHAR(32)   NOT NULL,
    ts          TIMESTAMPTZ   NOT NULL,
    type        VARCHAR(8)    NOT NULL,
    price       NUMERIC(19,4) NOT NULL,
    verdict     VARCHAR(16)   NOT NULL,
    reason      VARCHAR(128)
);
CREATE INDEX idx_bt_signals_run ON backtest_signals (run_id, figi, strategy_id);
--rollback DROP TABLE backtest_signals;

--changeset a.s.brovko:20261013-5
--comment Per-bar equity curve of a batch backtest pair.
CREATE TABLE backtest_equity (
    id          BIGSERIAL     NOT NULL PRIMARY KEY,
    run_id      VARCHAR(64)   NOT NULL,
    strategy_id VARCHAR(64)   NOT NULL,
    figi        VARCHAR(32)   NOT NULL,
    ts          TIMESTAMPTZ   NOT NULL,
    equity      NUMERIC(19,4) NOT NULL
);
CREATE INDEX idx_bt_equity_run ON backtest_equity (run_id, strategy_id, figi);
--rollback DROP TABLE backtest_equity;

--changeset a.s.brovko:20261013-6
--comment Batch header no longer carries a single pair's params/metrics (moved to backtest_pair_results).
ALTER TABLE backtest_runs ALTER COLUMN params DROP NOT NULL;
ALTER TABLE backtest_runs ALTER COLUMN metrics DROP NOT NULL;
--rollback ALTER TABLE backtest_runs ALTER COLUMN metrics SET NOT NULL;
--rollback ALTER TABLE backtest_runs ALTER COLUMN params SET NOT NULL;

--changeset a.s.brovko:20261013-7
--comment Batch header is instrument-agnostic; instrument_type lives per pair in backtest_pair_results.
ALTER TABLE backtest_runs ALTER COLUMN instrument_type DROP NOT NULL;
--rollback ALTER TABLE backtest_runs ALTER COLUMN instrument_type SET NOT NULL;