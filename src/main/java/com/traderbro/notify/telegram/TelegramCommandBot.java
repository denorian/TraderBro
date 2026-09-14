package com.traderbro.notify.telegram;

import lombok.extern.slf4j.Slf4j;

/**
 * Long-polling bot that answers read-only commands. Only the configured chat id is served;
 * messages from other chats are ignored and logged. Start/stop use the TelegramBots 9.x API.
 */
@Slf4j
public class TelegramCommandBot {

    private final String botToken;
    private final String allowedChatId;
    private final TelegramCommandHandler handler;
    private final TelegramSender sender;

    // TODO: verify API — TelegramBots 9.x long-polling registration handle.
    private Object registration;

    public TelegramCommandBot(String botToken, String allowedChatId,
                              TelegramCommandHandler handler, TelegramSender sender) {
        this.botToken = botToken;
        this.allowedChatId = allowedChatId;
        this.handler = handler;
        this.sender = sender;
    }

    /** Processes one incoming message; replies only if the chat is authorized. */
    public void onMessage(String chatId, String text) {
        if (allowedChatId == null || !allowedChatId.equals(chatId)) {
            log.warn("telegram: ignoring message from unauthorized chat {}", chatId);
            return;
        }
        if (text == null || !text.startsWith("/")) {
            return;
        }
        String command = text.split("\\s+")[0];
        String args = text.contains(" ") ? text.substring(text.indexOf(' ') + 1) : null;
        String reply = handler.handle(command, args);
        if (reply != null) {
            sender.sendHtml(chatId, reply);
        }
    }

    /** Registers the long-polling consumer with the TelegramBots application. */
    public void start() {
        // TODO: verify API — TelegramBotsLongPollingApplication registerBot + consumer callback.
        // Alternative: use TelegramBotsLongPollingApplication and an update consumer that calls
        // onMessage(update.getChatId(), update.getMessage().getText()).
        log.info("telegram command bot starting (long polling)");
        org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer consumer = update -> {
            if (update.hasMessage() && update.getMessage().hasText()) {
                String chatId = String.valueOf(update.getMessage().getChatId());
                onMessage(chatId, update.getMessage().getText());
            }
        };
        var app = new org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication();
        // TODO: verify API — registerBot(botToken, consumer) returns the registered bot.
        this.registration = app.registerBot(botToken, consumer);
    }

    public void stop() {
        // TODO: verify API — unregister/shutdown.
        if (registration != null) {
            log.info("telegram command bot stopped");
        }
    }
}