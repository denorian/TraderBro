package com.traderbro.signalanalysis.domain;

/**
 * Canonical, normalized content of a message used for the append-only version history. This is
 * the value over which the SHA-256 {@code contentHash} is computed, so an author returning to a
 * previous text after a later edit still yields a distinct version. JSON payloads are stored as
 * stable strings produced by the TDLib message mapper; never raw {@code TdApi} output.
 */
public record TelegramMessageContent(
        String contentType,
        String textContent,
        String formattedEntities,
        String mediaMetadata,
        String forwardMetadata) {

    public boolean hasAnyField() {
        return textContent != null
                || formattedEntities != null
                || mediaMetadata != null
                || forwardMetadata != null;
    }
}