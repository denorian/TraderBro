package com.traderbro.signalanalysis.telegram;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Internal port for reading the personal Telegram account. Implementations are either the real
 * TDLib adapter ({@code telegram.tdlib}) or a {@code FakeTelegramUserClient} in tests. No method
 * exposes a TDLib/TdApi type; callers only ever see the domain snapshots above.
 */
public interface TelegramUserClient {

    /** Register the single consumer for all {@link TelegramUpdate} events (callbacks are cheap). */
    void onUpdate(Consumer<TelegramUpdate> consumer);

    /** Populate the local chat cache for a list (Main/Archive). Completes when the request is sent. */
    CompletableFuture<Void> loadChats(ChatList list, int limit);

    /** List chat ids currently loaded for a list. */
    CompletableFuture<List<Long>> getChatIds(ChatList list, int limit);

    /** Full chat snapshot by id. */
    CompletableFuture<TelegramChat> getChat(long chatId);

    /** A page of messages, reverse-chronological; {@code fromMessageId=0} starts at the newest. */
    CompletableFuture<List<TelegramMessage>> getChatHistory(long chatId, long fromMessageId, int limit);

    /** Full, consistent snapshot of a single message (used to reconcile edits). */
    CompletableFuture<TelegramMessage> getMessage(long chatId, long messageId);

    /** Graceful shutdown: stop new requests, flush, close the native client. */
    void closeAndWait();
}