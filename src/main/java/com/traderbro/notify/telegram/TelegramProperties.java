package com.traderbro.notify.telegram;

import com.traderbro.core.event.NotificationLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Telegram bot configuration ({@code telegram.*}). Secrets come only from env. */
@Getter
@Setter
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    /** Master switch. When false (or no token), the bot bean is not created. */
    private boolean enabled = false;
    /** Env var holding the bot token. */
    private String botTokenEnv = "TELEGRAM_BOT_TOKEN";
    /** Env var holding the allowed chat id. */
    private String chatIdEnv = "TELEGRAM_CHAT_ID";
    private NotificationLevel minLevel = NotificationLevel.INFO;
    private int maxMessagesPerMinute = 20;
    /** Retry backoff for failed sends: 5s, 30s, 5m. */
    private long retryAttempts = 3;
}