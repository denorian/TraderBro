package com.traderbro.api.config;

import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.KillSwitchStatusProvider;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.notify.NotificationStore;
import com.traderbro.notify.telegram.DefaultTelegramMessageFormatter;
import com.traderbro.notify.telegram.DefaultTelegramSender;
import com.traderbro.notify.telegram.TelegramCommandBot;
import com.traderbro.notify.telegram.TelegramCommandHandler;
import com.traderbro.notify.telegram.TelegramMessageFormatter;
import com.traderbro.notify.telegram.TelegramQueueWorker;
import com.traderbro.notify.telegram.TelegramSender;
import com.traderbro.storage.SignalRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Telegram delivery beans only when {@code telegram.enabled=true}. The bot token and
 * chat id are read strictly from environment variables; enabling Telegram without them is a
 * configuration error and fails fast.
 */
@Configuration
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public class TelegramConfig {

    @Bean
    public TelegramMessageFormatter telegramMessageFormatter() {
        return new DefaultTelegramMessageFormatter();
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("telegram.enabled=true but env " + name + " is not set");
        }
        return v;
    }

    @Bean
    public TelegramSender telegramSender(TelegramProperties props) {
        String token = requireEnv(props.getBotTokenEnv());
        // TODO: verify API — TelegramClient construction in TelegramBots 9.x.
        Object client = org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient.builder()
                .token(token).build();
        return new DefaultTelegramSender(client);
    }

    @Bean
    public TelegramQueueWorker telegramQueueWorker(TelegramProperties props,
                                                   NotificationStore store, TelegramSender sender,
                                                   TelegramMessageFormatter formatter) {
        String chatId = requireEnv(props.getChatIdEnv());
        return new TelegramQueueWorker(store, sender, formatter, chatId,
                props.getMaxMessagesPerMinute(), props.getRetryAttempts());
    }

    @Bean
    public TelegramCommandHandler telegramCommandHandler(PortfolioProvider portfolio,
                                                         BrokerGateway gateway,
                                                         KillSwitchStatusProvider killSwitch,
                                                         StreamHealth streamHealth,
                                                         SignalRepository signalRepository,
                                                         OrderStore orderStore) {
        return new TelegramCommandHandler(portfolio, gateway, killSwitch, streamHealth,
                signalRepository, orderStore);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public TelegramCommandBot telegramCommandBot(TelegramProperties props,
                                                 TelegramCommandHandler handler,
                                                 TelegramSender sender) {
        String token = requireEnv(props.getBotTokenEnv());
        String chatId = requireEnv(props.getChatIdEnv());
        return new TelegramCommandBot(token, chatId, handler, sender);
    }
}