--liquibase formatted sql

--changeset a.s.brovko:20261022-1
--comment Stage 3.6: crypto (Binance) columns on instruments; shares/futures unaffected.
ALTER TABLE instruments ADD COLUMN base_asset VARCHAR(16);
ALTER TABLE instruments ADD COLUMN quote_asset VARCHAR(16);
ALTER TABLE instruments ADD COLUMN tick_size NUMERIC(19,8);
ALTER TABLE instruments ADD COLUMN step_size NUMERIC(19,8);
ALTER TABLE instruments ADD COLUMN min_notional NUMERIC(19,8);
ALTER TABLE instruments ADD COLUMN onboard_date DATE;
ALTER TABLE instruments ADD COLUMN market_cap_usd NUMERIC(24,2);
ALTER TABLE instruments ADD COLUMN market_cap_rank INTEGER;
ALTER TABLE instruments ADD COLUMN market_cap_updated_at TIMESTAMPTZ;
--rollback
--ALTER TABLE instruments DROP COLUMN market_cap_updated_at;
--ALTER TABLE instruments DROP COLUMN market_cap_rank;
--ALTER TABLE instruments DROP COLUMN market_cap_usd;
--ALTER TABLE instruments DROP COLUMN onboard_date;
--ALTER TABLE instruments DROP COLUMN min_notional;
--ALTER TABLE instruments DROP COLUMN step_size;
--ALTER TABLE instruments DROP COLUMN tick_size;
--ALTER TABLE instruments DROP COLUMN quote_asset;
--ALTER TABLE instruments DROP COLUMN base_asset;