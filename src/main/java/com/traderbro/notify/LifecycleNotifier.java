package com.traderbro.notify;

import com.traderbro.api.config.AppProperties;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Publishes APP_STARTED on startup and APP_STOPPED on graceful shutdown. */
@Slf4j
@RequiredArgsConstructor
public class LifecycleNotifier {

    private final TraderEventPublisher publisher;
    private final AppProperties app;

    @PostConstruct
    public void start() {
        publisher.publish(TraderEvent.of(NotificationType.APP_STARTED, NotificationLevel.INFO,
                Map.of("mode", app.isSandbox() ? "sandbox" : "live",
                        "version", System.getenv().getOrDefault("BUILD_GIT_HASH", "dev"))));
        log.info("lifecycle: APP_STARTED published");
    }

    @PreDestroy
    public void stop() {
        publisher.publish(TraderEvent.of(NotificationType.APP_STOPPED, NotificationLevel.INFO,
                Map.of("mode", app.isSandbox() ? "sandbox" : "live",
                        "version", System.getenv().getOrDefault("BUILD_GIT_HASH", "dev"))));
        log.info("lifecycle: APP_STOPPED published");
    }
}