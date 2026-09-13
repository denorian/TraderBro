package com.traderbro.scheduler;

import com.traderbro.core.strategies.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Rereads {@code strategy_config} so parameter changes take effect without a restart. */
@Slf4j
@RequiredArgsConstructor
public class StrategyReloadScheduler {

    private final StrategyRegistry registry;

    @Scheduled(fixedDelayString = "${scheduler.strategy-reload-ms:300000}")
    public void run() {
        try {
            registry.reload();
        } catch (RuntimeException e) {
            log.error("strategy config reload failed: {}", e.getMessage());
        }
    }
}