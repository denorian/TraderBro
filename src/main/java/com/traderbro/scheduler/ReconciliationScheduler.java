package com.traderbro.scheduler;

import com.traderbro.execution.reconcile.Reconciler;
import com.traderbro.execution.state.StateRecovery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically reconciles internal positions/orders with the broker. */
@Slf4j
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final Reconciler reconciler;
    private final StateRecovery stateRecovery;

    @Scheduled(fixedDelayString = "${scheduler.reconcile-ms:60000}")
    public void run() {
        if (stateRecovery.isObserveOnly()) {
            return;
        }
        try {
            reconciler.reconcile();
        } catch (RuntimeException e) {
            log.error("reconciliation run failed: {}", e.getMessage());
        }
    }
}