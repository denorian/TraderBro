package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Position;
import java.util.List;
import java.util.Optional;

/**
 * Channel for order placement and account/position retrieval.
 * Implemented by the T-Bank Invest API client ({@code data} package).
 * <p>This is the only gateway TraderBro talks to; execution never depends on a broker SDK.
 */
public interface BrokerGateway {

    /** Result of posting an order. */
    record OrderResponse(String brokerOrderId, String status, String message) {}

    /** Submits an order. Implementations are responsible for mapping domain → SDK. */
    OrderResponse postOrder(com.traderbro.core.domain.OrderRequest request);

    /** Cancels an open order by broker id. */
    void cancelOrder(String brokerOrderId);

    /** Current positions as reported by the broker. */
    List<Position> getPositions();

    /** Open (non-terminal) orders as reported by the broker. */
    List<com.traderbro.core.domain.OrderState> getOpenOrders();

    /**
     * Optional: credits the sandbox account. Returns true if supported/enabled.
     * No-op (false) in live mode.
     */
    boolean creditSandbox(java.math.BigDecimal amount);

    /** Instrument reference lookup, used by reconciliation and caching. */
    Optional<Instrument> instrumentByFigi(String figi);
}