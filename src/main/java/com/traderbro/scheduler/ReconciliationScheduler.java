package com.traderbro.scheduler;

import com.traderbro.execution.reconcile.Reconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically reconciles internal positions/orders with the broker. */
@Slf4j
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final Reconciler reconciler;

    @Scheduled(fixedDelayString = "${scheduler.reconcile-ms:60000}")
    public void run() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException e) {
            log.error("reconciliation run failed: {}", e.getMessage());
        }
    }
}