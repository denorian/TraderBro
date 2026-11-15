package com.traderbro.signalanalysis.telegram;

import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import java.time.Instant;

/**
 * Canonical, normalized snapshot of a message as handed to the ingest pipeline. Produced by the
 * TDLib adapter from a {@code TdApi.Message}; never exposes raw TDLib objects outside the
 * {@code telegram.tdlib} package.
 */
public record TelegramMessage(
        long messageId,
        long chatId,
        String senderType,
        Long senderId,
        String authorSignature,
        Instant sentAt,
        Instant editedAt,
        Long replyToMessageId,
        TelegramMessageContent content) {

    public boolean isEdited() {
        return editedAt != null;
    }
}