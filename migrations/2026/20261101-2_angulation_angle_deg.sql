--liquibase formatted sql

--changeset a.s.brovko:20261101-2
--comment Geometric angulation angle in degrees (atan of the ATR-normalised slope difference),
--        so the signal maps to the practitioner's ">45°, closer to 90°" semantics.
ALTER TABLE angulation_events ADD COLUMN angle_deg DOUBLE PRECISION;
--rollback ALTER TABLE angulation_events DROP COLUMN angle_deg;