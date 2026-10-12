--liquibase formatted sql

--changeset a.s.brovko:20260913-1
--comment Enable TimescaleDB extension for the bars hypertable.
CREATE EXTENSION IF NOT EXISTS timescaledb;
--rollback DROP EXTENSION IF EXISTS timescaledb;