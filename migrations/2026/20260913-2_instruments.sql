--liquibase formatted sql

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