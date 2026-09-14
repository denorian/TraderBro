package com.traderbro.execution.order;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.OrderRequest;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Position;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderFillEvent;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.risk.RiskContext;
import com.traderbro.core.risk.RiskDecision;
import com.traderbro.core.risk.RiskGate;
import com.traderbro.execution.killswitch.KillSwitch;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The only component that places orders. Enforces the architecture invariant:
 * {@code OrderManager} is the sole caller of {@link BrokerGateway#postOrder}, and it does so
 * only after {@link RiskGate} allows.
 *
 * <p>Responsibilities: idempotency by client UUID, status machine
 * {@code NEW -> SENT -> PARTIALLY_FILLED -> FILLED / CANCELLED / REJECTED / EXPIRED},
 * partial-fill handling from the order stream, order lifetime timeout with cancel, and
 * explicit <em>no auto-retry</em> of submissions (status is reconciled via getOpenOrders).
 */
@Slf4j
public class OrderManager {

    private final BrokerGateway gateway;
    private final RiskGate riskGate;
    private final OrderStore orderStore;
    private final PortfolioProvider portfolioProvider;
    private final KillSwitch killSwitch;
    private final Supplier<Duration> streamLag;
    private final Duration orderTtl;
    private final int moneyScale;
    private final Consumer<String> auditSink;
    private final TraderEventPublisher publisher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "order-timeout");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedDeque<Long> submissionTimes = new ConcurrentLinkedDeque<>();

    public OrderManager(BrokerGateway gateway, RiskGate riskGate, OrderStore orderStore,
                        PortfolioProvider portfolioProvider, OrderEventSource eventSource,
                        KillSwitch killSwitch, Supplier<Duration> streamLag,
                        Duration orderTtl, int moneyScale, Consumer<String> auditSink,
                        TraderEventPublisher publisher) {
        this.gateway = gateway;
        this.riskGate = riskGate;
        this.orderStore = orderStore;
        this.portfolioProvider = portfolioProvider;
        this.killSwitch = killSwitch;
        this.streamLag = streamLag;
        this.orderTtl = orderTtl;
        this.moneyScale = moneyScale;
        this.auditSink = auditSink;
        this.publisher = publisher;
        eventSource.subscribe(this::handleFill);
    }

    /**
     * Validates and submits an order. Idempotent by {@code clientOrderId}.
     *
     * @return the resulting {@link Order}; status is REJECTED if the risk gate denied.
     */
    public Order submit(OrderRequest req) {
        UUID clientId = req.getClientOrderId() == null || req.getClientOrderId().isBlank()
                ? UUID.randomUUID()
                : UUID.fromString(req.getClientOrderId());

        // Deduplication: a persisted order with this id must not be re-submitted.
        Optional<Order> existing = orderStore.findById(clientId);
        if (existing.isPresent()) {
            auditSink.accept("order: duplicate clientOrderId=" + clientId + " ignored");
            return existing.get();
        }

        Order order = Order.builder()
                .id(clientId)
                .figi(req.getFigi())
                .side(req.getSide())
                .type(req.getType())
                .requestedLots(req.getLots())
                .limitPrice(req.getLimitPrice())
                .status(OrderStatus.NEW)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .strategyId(req.getStrategyId())
                .build();
        orderStore.save(order);

        RiskDecision decision = evaluateRisk(req);
        if (!decision.isAllowed()) {
            orderStore.update(order.withStatus(OrderStatus.REJECTED)
                    .withReason(decision.getRule() + ": " + decision.getReason())
                    .withUpdatedAt(Instant.now()));
            auditSink.accept("order: clientOrderId=" + clientId + " REJECTED by " + decision.getRule());
            return order.withStatus(OrderStatus.REJECTED)
                    .withReason(decision.getRule() + ": " + decision.getReason());
        }

        try {
            BrokerGateway.OrderResponse resp = gateway.postOrder(toGatewayRequest(req, clientId));
            Order sent = order.withStatus(OrderStatus.SENT)
                    .withBrokerOrderId(resp.brokerOrderId())
                    .withUpdatedAt(Instant.now());
            orderStore.update(sent);
            recordSubmission();
            scheduleTimeout(sent);
            return sent;
        } catch (RuntimeException e) {
            log.error("order submission failed for clientOrderId={}: {}", clientId, e.getMessage());
            Order rejected = order.withStatus(OrderStatus.REJECTED)
                    .withReason("broker submission error: " + e.getMessage())
                    .withUpdatedAt(Instant.now());
            orderStore.update(rejected);
            auditSink.accept("order: clientOrderId=" + clientId + " REJECTED, submission error");
            publishOrderEvent(NotificationType.ORDER_REJECTED, rejected, 0, null,
                    "broker submission error: " + e.getMessage());
            return rejected;
        }
    }

    /** Cancels an open order by client id. */
    public void cancel(UUID clientId) {
        Order order = orderStore.findById(clientId).orElse(null);
        if (order == null || order.getStatus().isTerminal()) {
            return;
        }
        if (order.getBrokerOrderId() == null) {
            orderStore.update(order.withStatus(OrderStatus.CANCELLED)
                    .withReason("cancelled before submission").withUpdatedAt(Instant.now()));
            publishOrderEvent(NotificationType.ORDER_CANCELLED, order, order.getFilledLots(),
                    order.getAvgFillPrice(), "cancelled before submission");
            return;
        }
        try {
            gateway.cancelOrder(order.getBrokerOrderId());
            orderStore.update(order.withStatus(OrderStatus.CANCELLED).withUpdatedAt(Instant.now()));
            publishOrderEvent(NotificationType.ORDER_CANCELLED, order, order.getFilledLots(),
                    order.getAvgFillPrice(), "manual/ttl cancel");
        } catch (RuntimeException e) {
            auditSink.accept("order: cancel failed for " + clientId + ": " + e.getMessage());
            log.warn("cancel failed for {}: {}", clientId, e.getMessage());
        }
    }

    /** Cancels all open orders (invoked by the kill-switch). */
    public void cancelAll() {
        for (Order o : orderStore.findOpen()) {
            cancel(o.getId());
        }
    }

    private RiskDecision evaluateRisk(OrderRequest req) {
        Instant now = Instant.now();
        Instrument inst = gateway.instrumentByFigi(req.getFigi())
                .orElseThrow(() -> new IllegalStateException("unknown instrument figi=" + req.getFigi()));
        Portfolio pf = portfolioProvider.snapshot();
        List<Position> positions = portfolioProvider.positions();

        BigDecimal instrumentPosValue = BigDecimal.ZERO;
        BigDecimal totalExposure = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal val = p.getCurrentPrice() != null
                    ? p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getShares()))
                    : BigDecimal.ZERO;
            totalExposure = totalExposure.add(val);
            if (req.getFigi().equals(p.getFigi())) {
                instrumentPosValue = instrumentPosValue.add(val);
            }
        }

        BigDecimal price = req.getLimitPrice() != null ? req.getLimitPrice() : BigDecimal.ZERO;
        // TODO(stage2): aggregate currentFuturesMargin from the portfolio provider once futures
        // margin is tracked; the GO cap is currently enforced by FuturesPositionSizer at sizing.
        RiskContext ctx = new RiskContext(req.getFigi(), inst, req.getSide(), req.getLots(), price,
                pf, instrumentPosValue, totalExposure, ordersLastMinute(),
                killSwitch.isActive(), now, streamLag.get(), inst.getInstrumentType(), BigDecimal.ZERO);
        return riskGate.evaluate(ctx);
    }

    private void handleFill(OrderFillEvent event) {
        for (Order o : orderStore.findOpen()) {
            if (event.brokerOrderId().equals(o.getBrokerOrderId())) {
                Order updated = o.withFilledLots(event.filledLots())
                        .withAvgFillPrice(event.avgFillPrice())
                        .withStatus(event.status())
                        .withUpdatedAt(Instant.now());
                orderStore.update(updated);
                log.info("order {} -> {} (filled {} lots, avg {})", o.getId(), event.status(),
                        event.filledLots(), event.avgFillPrice());
                publishOrderEvent(event.status() == OrderStatus.FILLED
                        ? NotificationType.ORDER_FILLED : NotificationType.ORDER_PARTIALLY_FILLED,
                        o, event.filledLots(), event.avgFillPrice(), null);
                return;
            }
        }
    }

    private void publishOrderEvent(NotificationType type, Order o, long filledLots,
                                   java.math.BigDecimal avg, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticker", o.getFigi());
        payload.put("orderId", o.getId());
        payload.put("dirMark", o.getSide() == com.traderbro.core.domain.enums.OrderSide.BUY ? "📈 LONG" : "📉 SHORT");
        payload.put("filledLots", filledLots);
        payload.put("requestedLots", o.getRequestedLots());
        payload.put("avgPrice", avg);
        payload.put("reason", reason);
        payload.put("qtyUnit", "лот");
        publisher.publish(TraderEvent.of(type, NotificationLevel.INFO, payload));
    }

    private void scheduleTimeout(Order order) {
        scheduler.schedule(() -> {
            Order current = orderStore.findById(order.getId()).orElse(null);
            if (current != null && !current.getStatus().isTerminal()
                    && current.getStatus() != OrderStatus.NEW) {
                log.info("order {} exceeded TTL {}; cancelling", current.getId(), orderTtl);
                cancel(current.getId());
            }
        }, orderTtl.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordSubmission() {
        submissionTimes.addLast(System.currentTimeMillis());
        long cutoff = System.currentTimeMillis() - 60_000;
        while (!submissionTimes.isEmpty() && submissionTimes.peekFirst() < cutoff) {
            submissionTimes.pollFirst();
        }
    }

    private int ordersLastMinute() {
        long cutoff = System.currentTimeMillis() - 60_000;
        int count = 0;
        for (Long t : submissionTimes) {
            if (t >= cutoff) {
                count++;
            }
        }
        return count;
    }

    private OrderRequest toGatewayRequest(OrderRequest req, UUID clientId) {
        return OrderRequest.builder()
                .clientOrderId(clientId.toString())
                .figi(req.getFigi())
                .side(req.getSide())
                .type(req.getType())
                .lots(req.getLots())
                .limitPrice(req.getLimitPrice())
                .strategyId(req.getStrategyId())
                .build();
    }
}