--liquibase formatted sql

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