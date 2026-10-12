--liquibase formatted sql

--changeset a.s.brovko:20260916-1
--comment Widen instrument board column: SDK board values (exchange/realExchange) exceed varchar(32).
ALTER TABLE instruments ALTER COLUMN board TYPE VARCHAR(128);
--rollback ALTER TABLE instruments ALTER COLUMN board TYPE VARCHAR(32);