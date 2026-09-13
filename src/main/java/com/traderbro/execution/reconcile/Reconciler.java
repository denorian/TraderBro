package com.traderbro.execution.reconcile;

import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.Position;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PositionLedger;
import com.traderbro.execution.killswitch.KillSwitch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically compares our internal expectation of positions/open orders with the broker.
 * A discrepancy triggers an alert and, when configured, an automatic kill-switch.
 */
@Slf4j
@RequiredArgsConstructor
public class Reconciler {

    private final BrokerGateway gateway;
    private final PositionLedger ledger;
    private final OrderStore orderStore;
    private final KillSwitch killSwitch;
    private final boolean autoKillOnDiscrepancy;
    private final Consumer<String> alertSink;

    /** Runs one reconciliation pass. Returns true if consistent. */
    public boolean reconcile() {
        Map<String, Long> brokerShares = new HashMap<>();
        for (Position p : gateway.getPositions()) {
            brokerShares.put(p.getFigi(), p.getShares());
        }
        Map<String, Long> expected = ledger.expectedShares();

        boolean positionsConsistent = brokerShares.equals(expected);
        boolean ordersConsistent = openOrdersConsistent();

        if (!positionsConsistent) {
            String msg = "RECONCILIATION MISMATCH positions: broker=" + brokerShares
                    + " internal=" + expected;
            alertSink.accept(msg);
            log.error(msg);
            if (autoKillOnDiscrepancy) {
                killSwitch.activate("reconciliation position discrepancy");
            }
        }
        if (!ordersConsistent) {
            String msg = "RECONCILIATION MISMATCH open orders between broker and internal store";
            alertSink.accept(msg);
            log.error(msg);
            if (autoKillOnDiscrepancy) {
                killSwitch.activate("reconciliation order discrepancy");
            }
        }
        return positionsConsistent && ordersConsistent;
    }

    private boolean openOrdersConsistent() {
        var brokerOpen = gateway.getOpenOrders();
        var internalOpen = orderStore.findOpen().stream()
                .filter(o -> o.getBrokerOrderId() != null)
                .map(Order::getBrokerOrderId)
                .toList();
        var brokerIds = brokerOpen.stream()
                .map(com.traderbro.core.domain.OrderState::getBrokerOrderId)
                .toList();
        return brokerIds.size() == internalOpen.size() && brokerIds.containsAll(internalOpen);
    }

    /** The reconciler's active toggle (used by the scheduler). */
    public boolean isEnabled() {
        return true;
    }
}