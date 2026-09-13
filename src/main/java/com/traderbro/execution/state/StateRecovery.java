package com.traderbro.execution.state;

import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PositionLedger;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Restores execution state after a restart: re-reads open orders and expected positions from
 * the database and reconciles them with the broker. On any mismatch the application starts in
 * <em>observe-only</em> mode: signals are still computed but no orders are placed until manual
 * confirmation.
 */
@Slf4j
public class StateRecovery {

    private final BrokerGateway gateway;
    private final OrderStore orderStore;
    private final PositionLedger ledger;
    private final AtomicBoolean observeOnly = new AtomicBoolean(false);
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    public StateRecovery(BrokerGateway gateway, OrderStore orderStore, PositionLedger ledger) {
        this.gateway = gateway;
        this.orderStore = orderStore;
        this.ledger = ledger;
    }

    /**
     * Runs recovery at startup. Must be invoked once after all beans are wired.
     */
    public synchronized void recover() {
        try {
            var internalOrders = orderStore.findOpen();
            var brokerOrders = gateway.getOpenOrders();
            if (!internalOrders.isEmpty() && brokerOrders.isEmpty()) {
                // We think we have open orders but the broker has none -> unsafe divergence.
                log.warn("StateRecovery: {} internal open orders but broker reports none", internalOrders.size());
                observeOnly.set(true);
            }
            var brokerShares = gateway.getPositions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.traderbro.core.domain.Position::getFigi,
                            com.traderbro.core.domain.Position::getShares));
            if (!brokerShares.equals(ledger.expectedShares())) {
                log.warn("StateRecovery: position ledger does not match broker; observe-only mode");
                observeOnly.set(true);
            }
        } catch (RuntimeException e) {
            log.error("StateRecovery: failed to reconcile, entering observe-only mode: {}", e.getMessage());
            observeOnly.set(true);
        } finally {
            recovered.set(true);
            log.info("StateRecovery complete. observeOnly={}", observeOnly.get());
        }
    }

    /** True when the engine must not place orders (observe-only). */
    public boolean isObserveOnly() {
        return observeOnly.get();
    }

    public boolean isRecovered() {
        return recovered.get();
    }
}