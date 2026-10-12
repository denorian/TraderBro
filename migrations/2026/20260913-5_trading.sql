--liquibase formatted sql

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