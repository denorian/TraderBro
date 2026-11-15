--liquibase formatted sql

-- ============================================================================
-- Stage 4.0 signal analysis — personal Telegram account ingestion.
-- Tables: telegram_channels, telegram_channel_sync_state, telegram_messages,
--         telegram_message_versions, telegram_ingest_events, telegram_sync_runs.
-- Telegram IDs are BIGINT; timestamps are TIMESTAMPTZ stored in UTC; internal
-- PK/FK are UUID; structured entities/raw payloads are JSONB.
-- ============================================================================

--changeset a.s.brovko:20261108-1
--comment Signal analysis - broadcast channel metadata (Main + Archive subscriptions).
CREATE TABLE telegram_channels (
    id                      UUID        NOT NULL PRIMARY KEY,
    telegram_chat_id        BIGINT      NOT NULL,
    telegram_supergroup_id  BIGINT,
    title                   TEXT        NOT NULL,
    username                TEXT,
    is_channel              BOOLEAN     NOT NULL DEFAULT TRUE,
    is_subscribed           BOOLEAN     NOT NULL,
    is_archived             BOOLEAN     NOT NULL DEFAULT FALSE,
    membership_status       TEXT        NOT NULL,
    monitoring_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    monitoring_updated_at   TIMESTAMPTZ,
    first_seen_at           TIMESTAMPTZ NOT NULL,
    last_seen_at            TIMESTAMPTZ NOT NULL,
    subscription_synced_at  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL
);
ALTER TABLE telegram_channels ADD CONSTRAINT uq_telegram_channels_chat_id UNIQUE (telegram_chat_id);
CREATE INDEX idx_telegram_channels_subscribed ON telegram_channels (is_subscribed, monitoring_enabled);
--rollback DROP TABLE telegram_channels;

--changeset a.s.brovko:20261108-2
--comment Signal analysis - resumable history/backfill cursor per monitored channel.
CREATE TABLE telegram_channel_sync_state (
    channel_id                       UUID        NOT NULL PRIMARY KEY REFERENCES telegram_channels (id),
    oldest_loaded_message_id         BIGINT,
    oldest_loaded_message_sent_at    TIMESTAMPTZ,
    newest_loaded_message_id         BIGINT,
    full_backfill_completed          BOOLEAN     NOT NULL DEFAULT FALSE,
    last_history_request_message_id  BIGINT,
    last_live_event_at               TIMESTAMPTZ,
    last_tail_sync_at                TIMESTAMPTZ,
    last_successful_sync_at          TIMESTAMPTZ,
    last_error_code                  TEXT,
    last_error_message               TEXT,
    sync_attempts                    INTEGER     NOT NULL DEFAULT 0,
    updated_at                       TIMESTAMPTZ NOT NULL
);
--rollback DROP TABLE telegram_channel_sync_state;

--changeset a.s.brovko:20261108-3
--comment Signal analysis - current message projection; deletion marks availability but keeps content.
CREATE TABLE telegram_messages (
    id                     UUID         NOT NULL PRIMARY KEY,
    channel_id             UUID         NOT NULL REFERENCES telegram_channels (id),
    telegram_message_id    BIGINT       NOT NULL,
    sender_type            TEXT,
    sender_id              BIGINT,
    author_signature       TEXT,
    sent_at                TIMESTAMPTZ  NOT NULL,
    source_edited_at       TIMESTAMPTZ,
    reply_to_message_id    BIGINT,
    forward_metadata       JSONB,
    content_type           TEXT         NOT NULL,
    text_content           TEXT,
    formatted_entities     JSONB,
    media_metadata         JSONB,
    content_hash           VARCHAR(64)  NOT NULL,
    current_version_no     INTEGER      NOT NULL,
    availability_status    TEXT         NOT NULL,
    first_observed_at      TIMESTAMPTZ  NOT NULL,
    last_observed_at       TIMESTAMPTZ  NOT NULL,
    deletion_observed_at   TIMESTAMPTZ,
    is_permanently_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
ALTER TABLE telegram_messages ADD CONSTRAINT uq_telegram_messages_channel_msg UNIQUE (channel_id, telegram_message_id);
CREATE INDEX idx_telegram_messages_channel_sent ON telegram_messages (channel_id, sent_at DESC);
CREATE INDEX idx_telegram_messages_deleted ON telegram_messages (availability_status);
--rollback DROP TABLE telegram_messages;

--changeset a.s.brovko:20261108-4
--comment Signal analysis - append-only version history; a return to a previous text is a new version.
CREATE TABLE telegram_message_versions (
    id                 UUID         NOT NULL PRIMARY KEY,
    message_id         UUID         NOT NULL REFERENCES telegram_messages (id),
    version_no         INTEGER      NOT NULL,
    change_type        TEXT         NOT NULL,
    source_edited_at   TIMESTAMPTZ,
    observed_at        TIMESTAMPTZ  NOT NULL,
    content_type       TEXT         NOT NULL,
    text_content       TEXT,
    formatted_entities JSONB,
    media_metadata     JSONB,
    forward_metadata   JSONB,
    content_hash       VARCHAR(64)  NOT NULL,
    source_event_id    UUID,
    created_at         TIMESTAMPTZ  NOT NULL
);
ALTER TABLE telegram_message_versions ADD CONSTRAINT uq_telegram_versions_msg_no UNIQUE (message_id, version_no);
CREATE INDEX idx_telegram_versions_message ON telegram_message_versions (message_id);
--rollback DROP TABLE telegram_message_versions;

--changeset a.s.brovko:20261108-5
--comment Signal analysis - durable inbox; at-least-once, idempotent by dedup_key; raw payload retained only briefly.
CREATE TABLE telegram_ingest_events (
    received_seq        BIGSERIAL    NOT NULL PRIMARY KEY,
    id                  UUID         NOT NULL,
    event_type          TEXT         NOT NULL,
    telegram_chat_id    BIGINT       NOT NULL,
    telegram_message_id BIGINT,
    dedup_key           TEXT,
    payload             JSONB        NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    status              TEXT         NOT NULL,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    available_at        TIMESTAMPTZ  NOT NULL,
    locked_at           TIMESTAMPTZ,
    locked_by           TEXT,
    processed_at        TIMESTAMPTZ,
    last_error          TEXT
);
ALTER TABLE telegram_ingest_events ADD CONSTRAINT uq_telegram_ingest_events_id UNIQUE (id);
ALTER TABLE telegram_ingest_events ADD CONSTRAINT uq_telegram_ingest_events_dedup UNIQUE (dedup_key);
CREATE INDEX idx_telegram_ingest_events_claim ON telegram_ingest_events (status, available_at, received_seq);
CREATE INDEX idx_telegram_ingest_events_chat_msg ON telegram_ingest_events (telegram_chat_id, telegram_message_id);
--rollback DROP TABLE telegram_ingest_events;

--changeset a.s.brovko:20261108-6
--comment Signal analysis - sync run ledger for discovery/backfill/tail reconcile, resumable after restart.
CREATE TABLE telegram_sync_runs (
    id              UUID        NOT NULL PRIMARY KEY,
    channel_id      UUID        REFERENCES telegram_channels (id),
    run_type        TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    cursor_message_id BIGINT,
    loaded_count    INTEGER     NOT NULL DEFAULT 0,
    created_count   INTEGER     NOT NULL DEFAULT 0,
    edited_count    INTEGER     NOT NULL DEFAULT 0,
    error_code      TEXT,
    error_message   TEXT
);
CREATE INDEX idx_telegram_sync_runs_channel ON telegram_sync_runs (channel_id, run_type);
--rollback DROP TABLE telegram_sync_runs;