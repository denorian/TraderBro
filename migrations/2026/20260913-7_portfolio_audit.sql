--liquibase formatted sql

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