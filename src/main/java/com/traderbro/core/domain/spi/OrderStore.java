package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.enums.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for orders. Implemented by the storage layer ({@code orders} table).
 * Orders must be persisted before submission so a restart cannot double-submit.
 */
public interface OrderStore {

    void save(Order order);

    void update(Order order);

    Optional<Order> findById(UUID id);

    /** Finds all non-terminal orders (NEW, SENT, PARTIALLY_FILLED). */
    List<Order> findOpen();

    /** True if a client order id was already persisted (deduplication). */
    boolean exists(UUID clientOrderId);

    /** Updates only the status and fill fields of an existing order (optimistic, guarded). */
    int updateStatus(UUID id, OrderStatus from, OrderStatus to);
}