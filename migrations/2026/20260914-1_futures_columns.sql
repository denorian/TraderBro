--liquibase formatted sql

--changeset a.s.brovko:20260914-1
--comment Stage 2: futures columns on instruments; existing shares migrate to SHARE.
ALTER TABLE instruments ADD COLUMN instrument_type VARCHAR(16) NOT NULL DEFAULT 'SHARE';
ALTER TABLE instruments ADD COLUMN basic_asset VARCHAR(32);
ALTER TABLE instruments ADD COLUMN min_price_increment_amount NUMERIC(19,4);
ALTER TABLE instruments ADD COLUMN expiration_date DATE;
ALTER TABLE instruments ADD COLUMN first_trade_date DATE;
ALTER TABLE instruments ADD COLUMN initial_margin NUMERIC(19,4);
UPDATE instruments SET instrument_type = 'SHARE' WHERE instrument_type IS NULL;
--rollback
--ALTER TABLE instruments DROP COLUMN initial_margin;
--ALTER TABLE instruments DROP COLUMN first_trade_date;
--ALTER TABLE instruments DROP COLUMN expiration_date;
--ALTER TABLE instruments DROP COLUMN min_price_increment_amount;
--ALTER TABLE instruments DROP COLUMN basic_asset;
--ALTER TABLE instruments DROP COLUMN instrument_type;