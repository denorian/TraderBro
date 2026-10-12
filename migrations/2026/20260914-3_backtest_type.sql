--liquibase formatted sql

--changeset a.s.brovko:20260914-3
--comment Stage 2: instrument type recorded on backtest runs.
ALTER TABLE backtest_runs ADD COLUMN instrument_type VARCHAR(16) NOT NULL DEFAULT 'SHARE';
--rollback ALTER TABLE backtest_runs DROP COLUMN instrument_type;