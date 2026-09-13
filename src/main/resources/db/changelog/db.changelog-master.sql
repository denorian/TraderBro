--liquibase formatted sql

-- ============================================================================
-- TraderBro — schema (Liquibase formatted SQL)
-- Changeset ids follow the convention YYYYMMDD-N.
-- ============================================================================

--changeset a.s.brovko:20260913-1
--comment Enable TimescaleDB extension for the bars hypertable.
CREATE EXTENSION IF NOT EXISTS timescaledb;
--rollback DROP EXTENSION IF EXISTS timescaledb;

--changeset a.s.brovko:20260913-2
--comment Reference data for tradable instruments.
CREATE TABLE instruments (
    figi                VARCHAR(32)  NOT NULL PRIMARY KEY,
    ticker              VARCHAR(16)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    isin                VARCHAR(16),
    currency            VARCHAR(8),
    lot                 INTEGER      NOT NULL,
    min_price_increment NUMERIC(19,4) NOT NULL,
    board               VARCHAR(32),
    tradable            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_instruments_ticker ON instruments (ticker);
--rollback DROP TABLE instruments;

--changeset a.s.brovko:20260913-3
--comment OHLCV bars table, converted to a TimescaleDB hypertable in the next changeset.
CREATE TABLE bars (
    figi     VARCHAR(32)   NOT NULL,
    interval VARCHAR(16)   NOT NULL,
    ts       TIMESTAMPTZ   NOT NULL,
    open     NUMERIC(19,4) NOT NULL,
    high     NUMERIC(19,4) NOT NULL,
    low      NUMERIC(19,4) NOT NULL,
    close    NUMERIC(19,4) NOT NULL,
    volume   BIGINT        NOT NULL DEFAULT 0,
    is_final BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_bars PRIMARY KEY (figi, interval, ts)
);
CREATE INDEX idx_bars_figi_ts ON bars (figi, ts);
--rollback DROP TABLE bars;

--changeset a.s.brovko:20260913-4 runInTransaction:false
--comment Convert bars into a TimescaleDB hypertable on ts.
SELECT create_hypertable('bars', 'ts', if_not_exists => TRUE, migrate_data => TRUE);
--rollback SELECT 1;

--changeset a.s.brovko:20260913-5
--comment Trading domain tables — signals, orders, trades.
CREATE TABLE signals (
    id                 UUID          NOT NULL PRIMARY KEY,
    created_at         TIMESTAMPTZ   NOT NULL,
    strategy_id        VARCHAR(64)   NOT NULL,
    figi               VARCHAR(32)   NOT NULL,
    ts                 TIMESTAMPTZ   NOT NULL,
    direction          VARCHAR(8)    NOT NULL,
    price              NUMERIC(19,4) NOT NULL,
    indicator_snapshot JSONB,
    verdict            VARCHAR(16)   NOT NULL,
    reason             VARCHAR(512)
);
CREATE INDEX idx_signals_figi_ts ON signals (figi, ts);

CREATE TABLE orders (
    id               UUID          NOT NULL PRIMARY KEY,
    figi             VARCHAR(32)   NOT NULL,
    side             VARCHAR(8)    NOT NULL,
    type             VARCHAR(8)    NOT NULL,
    requested_lots   BIGINT        NOT NULL,
    limit_price      NUMERIC(19,4),
    status           VARCHAR(24)   NOT NULL,
    broker_order_id  VARCHAR(64),
    filled_lots      BIGINT        NOT NULL DEFAULT 0,
    avg_fill_price   NUMERIC(19,4),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    strategy_id      VARCHAR(64),
    reason           VARCHAR(512)
);
CREATE INDEX idx_orders_status ON orders (status);
CREATE UNIQUE INDEX uq_orders_broker_order_id ON orders (broker_order_id);

CREATE TABLE trades (
    id         UUID          NOT NULL PRIMARY KEY,
    order_id   UUID          NOT NULL,
    figi       VARCHAR(32)   NOT NULL,
    side       VARCHAR(8)    NOT NULL,
    lots       BIGINT        NOT NULL,
    price      NUMERIC(19,4) NOT NULL,
    ts         TIMESTAMPTZ   NOT NULL,
    commission NUMERIC(19,4) NOT NULL DEFAULT 0
);
CREATE INDEX idx_trades_figi_ts ON trades (figi, ts);
--rollback DROP TABLE trades; DROP TABLE orders; DROP TABLE signals;

--changeset a.s.brovko:20260913-6
--comment Strategy configuration (hot-reloadable) and backtest runs.
CREATE TABLE strategy_config (
    id               VARCHAR(64)   NOT NULL PRIMARY KEY,
    enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    params           JSONB         NOT NULL,
    order_type       VARCHAR(8)    NOT NULL DEFAULT 'LIMIT',
    max_position_pct NUMERIC(19,4) NOT NULL DEFAULT 0.1000,
    interval         VARCHAR(16)   NOT NULL DEFAULT 'FIFTEEN_MIN',
    version          BIGINT        NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ   NOT NULL
);

CREATE TABLE backtest_runs (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    strategy_id  VARCHAR(64)   NOT NULL,
    figi         VARCHAR(32)   NOT NULL,
    interval     VARCHAR(16)   NOT NULL,
    from_ts      TIMESTAMPTZ   NOT NULL,
    to_ts        TIMESTAMPTZ   NOT NULL,
    params       JSONB         NOT NULL,
    metrics      JSONB         NOT NULL,
    code_version VARCHAR(64)   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    bars_count   BIGINT        NOT NULL DEFAULT 0
);
--rollback DROP TABLE backtest_runs; DROP TABLE strategy_config;

--changeset a.s.brovko:20260913-7
--comment Portfolio snapshots and audit events.
CREATE TABLE portfolio_snapshots (
    id               BIGSERIAL     NOT NULL PRIMARY KEY,
    ts               TIMESTAMPTZ   NOT NULL,
    total_value      NUMERIC(19,4) NOT NULL,
    cash             NUMERIC(19,4) NOT NULL DEFAULT 0,
    securities_value NUMERIC(19,4) NOT NULL DEFAULT 0,
    day_pnl          NUMERIC(19,4) NOT NULL DEFAULT 0,
    day_pnl_percent  NUMERIC(19,4) NOT NULL DEFAULT 0
);
CREATE INDEX idx_portfolio_snapshots_ts ON portfolio_snapshots (ts);

CREATE TABLE audit_events (
    id      BIGSERIAL   NOT NULL PRIMARY KEY,
    ts      TIMESTAMPTZ NOT NULL,
    level   VARCHAR(16) NOT NULL,
    source  VARCHAR(64) NOT NULL,
    message TEXT        NOT NULL
);
CREATE INDEX idx_audit_events_ts ON audit_events (ts);
--rollback DROP TABLE audit_events; DROP TABLE portfolio_snapshots;