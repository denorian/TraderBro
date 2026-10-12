--liquibase formatted sql

--changeset a.s.brovko:20260913-3
--comment OHLCV bars table; converted to a TimescaleDB hypertable in 20260913-4.
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