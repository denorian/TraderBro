package com.traderbro.notify.telegram;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends HTML messages through the TelegramBots 9.x client.
 * <p>NOTE: the TelegramBots 9.x API surface below is provisional and must be verified against
 * the pinned version ({@code // TODO: verify API}).
 */
@Slf4j
public class DefaultTelegramSender implements TelegramSender {

    private final Object telegramClient;

    public DefaultTelegramSender(Object telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public SendResult sendHtml(String chatId, String html) {
        try {
            // TODO: verify API — SendMessage builder + execute(TelegramClient) semantics.
            var message = org.telegram.telegrambots.meta.api.methods.send.SendMessage.builder()
                    .chatId(chatId)
                    .text(html)
                    .parseMode("HTML")
                    .build();
            var response = ((org.telegram.telegrambots.client.telegram.TelegramClient) telegramClient)
                    .execute(message);
            return SendResult.ok(String.valueOf(response.getMessageId()));
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException e) {
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