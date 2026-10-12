--liquibase formatted sql

--changeset a.s.brovko:20260913-4 runInTransaction:false
--comment Convert bars into a TimescaleDB hypertable on ts.
SELECT create_hypertable('bars', 'ts', if_not_exists => TRUE, migrate_data => TRUE);
--rollback SELECT 1;