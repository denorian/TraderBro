package com.traderbro.signalanalysis.telegram;

import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Scripted in-memory {@link TelegramUserClient} for contract/unit tests. Delivers a configurable
 * discovery (Main + Archive) and a message ledger; no network, no TDLib.
 */
public class FakeTelegramUserClient implements TelegramUserClient {

    private Consumer<TelegramUpdate> consumer = u -> { };
    private final List<TelegramChat> chats = new ArrayList<>();
    private final Map<Long, List<TelegramMessage>> history = new java.util.HashMap<>();
    private TelegramAuthState authState = TelegramAuthState.READY;
    private boolean historyLoadFailed;

    @Override
    public void onUpdate(Consumer<TelegramUpdate> consumer) {
        this.consumer = consumer;
    }

    @Override
    public CompletableFuture<Void> loadChats(ChatList list, int limit) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<Long>> getChatIds(ChatList list, int limit) {
        return CompletableFuture.completedFuture(chats.stream()
                .filter(c -> c.archived() == (list == ChatList.ARCHIVE))
                .map(TelegramChat::chatId).toList());
    }

    @Override
    public CompletableFuture<TelegramChat> getChat(long chatId) {
        return CompletableFuture.completedFuture(chats.stream()
                .filter(c -> c.chatId() == chatId).findFirst().orElse(null));
    }

    @Override
    public CompletableFuture<List<TelegramMessage>> getChatHistory(long chatId, long fromMessageId,
                                                                   int limit) {
        if (historyLoadFailed) {
            return CompletableFuture.failedFuture(new IllegalStateException("UNAVAILABLE"));
        }
        List<TelegramMessage> msgs = history.getOrDefault(chatId, List.of());
        List<TelegramMessage> page = msgs.stream()
                .filter(m -> fromMessageId == 0 || m.messageId() <= fromMessageId)
                .limit(limit)
                .toList();
        return CompletableFuture.completedFuture(page);
    }

    @Override
    public CompletableFuture<TelegramMessage> getMessage(long chatId, long messageId) {
        return CompletableFuture.completedFuture(history.getOrDefault(chatId, List.of()).stream()
                .filter(m -> m.messageId() == messageId).findFirst().orElse(null));
    }

    @Override
    public void closeAndWait() {
    }

    // ---------------------------------------------------------------- test helpers

    public FakeTelegramUserClient withChannel(TelegramChat chat) {
        chats.add(chat);
        return this;
    }

    public FakeTelegramUserClient withMessage(long chatId, long messageId, String text,
                                              Instant sentAt) {
        history.computeIfAbsent(chatId, k -> new ArrayList<>()).add(
                new TelegramMessage(messageId, chatId, "USER", 1L, null, sentAt, null, null,
                        new TelegramMessageContent("TEXT", text, null, null, null)));
        return this;
    }

    public FakeTelegramUserClient withAuth(TelegramAuthState state) {
        this.authState = state;
        return this;
    }

    public FakeTelegramUserClient withHistoryFailure() {
        this.historyLoadFailed = true;
        return this;
    }

    public void emit(TelegramUpdate update) {
        consumer.accept(update);
    }

    public TelegramAuthState authState() {
        return authState;
    }
}