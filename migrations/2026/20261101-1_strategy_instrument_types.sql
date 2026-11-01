--liquibase formatted sql

--changeset a.s.brovko:20261101-1
--comment Optional per-strategy instrument-type binding; NULL = strategy runs on all types.
ALTER TABLE strategy_config ADD COLUMN instrument_types TEXT[];
--rollback ALTER TABLE strategy_config DROP COLUMN instrument_types;