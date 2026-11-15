package com.traderbro.signalanalysis.telegram;

import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import java.time.Instant;

/**
 * Domain-neutral Telegram updates delivered to the ingestion pipeline. The TDLib adapter converts
 * native {@code TdApi.Update} subtypes into these; nothing outside {@code telegram.tdlib} sees a
 * TDLib type.
 */
public sealed interface TelegramUpdate {

    /** A new (or updated) full message arrived. */
    record NewMessage(TelegramMessage message) implements TelegramUpdate {
    }

    /** Only the edit timestamp changed ({@code updateMessageEdited}). */
    record MessageEdited(long chatId, long messageId, Instant editedAt) implements TelegramUpdate {
    }

    /** Only the content changed ({@code updateMessageContent}). */
    record MessageContent(long chatId, long messageId, TelegramMessageContent content)
            implements TelegramUpdate {
    }

    /** Messages were removed from the chat ({@code updateDeleteMessages}). */
    record DeleteMessages(long chatId, long[] messageIds, boolean permanent, boolean fromCache)
            implements TelegramUpdate {
    }

    /** A chat was added to the local cache ({@code updateNewChat}). */
    record NewChat(TelegramChat chat) implements TelegramUpdate {
    }

    /** Supergroup metadata changed ({@code updateSupergroup}). */
    record Supergroup(TelegramSupergroup supergroup) implements TelegramUpdate {
    }

    /** Authorization state transitioned ({@code updateAuthorizationState}). */
    record AuthorizationState(TelegramAuthState state) implements TelegramUpdate {
    }
}