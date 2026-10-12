--liquibase formatted sql

--changeset a.s.brovko:20261012-1
--comment Stage 3: per-bar indicator snapshots for the Alligator and Awesome Oscillator.
CREATE TABLE indicator_snapshots (
    figi      VARCHAR(32)  NOT NULL,
    interval  VARCHAR(16)  NOT NULL,
    ts        TIMESTAMPTZ  NOT NULL,
    indicator VARCHAR(16)  NOT NULL,
    values    JSONB        NOT NULL,
    CONSTRAINT pk_indicator_snapshots PRIMARY KEY (figi, interval, ts, indicator)
);
CREATE INDEX idx_indicator_snapshots_ts ON indicator_snapshots (ts);
CREATE INDEX idx_indicator_snapshots_figi_latest ON indicator_snapshots (figi, interval, ts);
--rollback DROP TABLE indicator_snapshots;