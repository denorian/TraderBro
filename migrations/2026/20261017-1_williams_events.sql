--liquibase formatted sql
--changeset a.s.brovko:20261017-1
--comment Stage 3.4: Bill Williams AO "Twin Peaks" divergence and angulation events.
CREATE TABLE divergence_events (
    id             UUID         NOT NULL,
    figi           VARCHAR(32)  NOT NULL,
    interval       VARCHAR(16)  NOT NULL,
    ts             TIMESTAMPTZ  NOT NULL,
    direction      VARCHAR(16)  NOT NULL,
    degree         INT          NOT NULL,
    ao_peak1_ts    TIMESTAMPTZ  NOT NULL,
    ao_peak1_value DOUBLE PRECISION NOT NULL,
    ao_peak2_ts    TIMESTAMPTZ  NOT NULL,
    ao_peak2_value DOUBLE PRECISION NOT NULL,
    price_at_peak1 DOUBLE PRECISION NOT NULL,
    price_at_peak2 DOUBLE PRECISION NOT NULL,
    strength       DOUBLE PRECISION NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    confirmed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_divergence_events PRIMARY KEY (id),
    CONSTRAINT uq_divergence_events UNIQUE (figi, interval, ao_peak2_ts, direction)
);
CREATE INDEX idx_divergence_events_ts ON divergence_events (ts);
CREATE INDEX idx_divergence_events_figi_latest ON divergence_events (figi, interval, ts);
--rollback DROP TABLE divergence_events;

--changeset a.s.brovko:20261017-2
--comment Angulation events anchored on the extreme peak of a divergence event.
CREATE TABLE angulation_events (
    figi               VARCHAR(32)  NOT NULL,
    interval           VARCHAR(16)  NOT NULL,
    ts                 TIMESTAMPTZ  NOT NULL,
    divergence_event_id UUID        NOT NULL REFERENCES divergence_events (id),
    direction          VARCHAR(16)  NOT NULL,
    price_slope_atr    DOUBLE PRECISION NOT NULL,
    jaw_slope_atr      DOUBLE PRECISION NOT NULL,
    angle_atr          DOUBLE PRECISION NOT NULL,
    anchor_ts          TIMESTAMPTZ  NOT NULL,
    bars_in_window     INT          NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_angulation_events PRIMARY KEY (figi, interval, ts, divergence_event_id)
);
CREATE INDEX idx_angulation_events_ts ON angulation_events (ts);
CREATE INDEX idx_angulation_events_div ON angulation_events (divergence_event_id);
--rollback DROP TABLE angulation_events;