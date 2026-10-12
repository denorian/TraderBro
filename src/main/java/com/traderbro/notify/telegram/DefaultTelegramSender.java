package com.traderbro.notify.telegram;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/**
 * Sends HTML messages through the TelegramBots 9.x {@link org.telegram.telegrambots.meta.generics.TelegramClient}.
 * <p>Map a Telegram 429 onto {@link TelegramSender.SendResult#rateLimited(long)} so the queue
 * worker can pause by the server's {@code retry_after}.
 */
@Slf4j
public class DefaultTelegramSender implements TelegramSender {

    private final org.telegram.telegrambots.meta.generics.TelegramClient telegramClient;

    public DefaultTelegramSender(org.telegram.telegrambots.meta.generics.TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public SendResult sendHtml(String chatId, String html) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(html)
                    .parseMode("HTML")
                    .build();
            var response = telegramClient.execute(message);
            return SendResult.ok(String.valueOf(response.getMessageId()));
        } catch (TelegramApiRequestException e) {
            Integer retryAfter = e.getParameters() != null ? e.getParameters().getRetryAfter() : null;
            if (retryAfter != null) {
                log.warn("telegram rate-limited, retry after {}s", retryAfter);
                return SendResult.rateLimited(retryAfter);
            }
            log.warn("telegram send failed for chat {}: {}", chatId, e.getMessage());
            return SendResult.failed();
        } catch (Exception e) {
            log.warn("telegram send failed for chat {}: {}", chatId, e.getMessage());
            return SendResult.failed();
        }
    }
}