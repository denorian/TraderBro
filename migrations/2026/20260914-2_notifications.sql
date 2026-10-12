--liquibase formatted sql

--changeset a.s.brovko:20260914-2
--comment Stage 2: persistent outbound notification queue.
CREATE TABLE notifications (
    id                  BIGSERIAL    NOT NULL PRIMARY KEY,
    created_at          TIMESTAMPTZ  NOT NULL,
    type                VARCHAR(32)  NOT NULL,
    level               VARCHAR(16)  NOT NULL,
    payload             JSONB        NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts            INT          NOT NULL DEFAULT 0,
    sent_at             TIMESTAMPTZ,
    telegram_message_id VARCHAR(64),
    dedup_key           VARCHAR(255)
);
CREATE INDEX idx_notifications_status ON notifications (status, created_at);
--rollback DROP TABLE notifications;