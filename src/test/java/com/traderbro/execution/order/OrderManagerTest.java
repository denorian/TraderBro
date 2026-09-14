package com.traderbro.execution.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.OrderRequest;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderFillEvent;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.risk.RiskDecision;
import com.traderbro.core.risk.RiskGate;
import com.traderbro.execution.killswitch.KillSwitch;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderManagerTest {

    private BrokerGateway gateway;
    private RiskGate riskGate;
    private OrderStore orderStore;
    private OrderManager manager;
    private AtomicReference<Consumer<OrderFillEvent>> onFill;

    @BeforeEach
    void setUp() {
        gateway = mock(BrokerGateway.class);
        riskGate = mock(RiskGate.class);
        orderStore = mock(OrderStore.class);
        PortfolioProvider portfolio = mock(PortfolioProvider.class);
        OrderEventSource eventSource = mock(OrderEventSource.class);
        onFill = new AtomicReference<>();
        when(eventSource.subscribe(any())).thenAnswer(inv -> {
            onFill.set(inv.getArgument(0));
            return null;
        });

        when(riskGate.evaluate(any())).thenReturn(RiskDecision.allow());
        when(gateway.postOrder(any())).thenReturn(
                new BrokerGateway.OrderResponse("broker-1", "EXECUTION_REPORT_STATUS_NEW", null));
        when(orderStore.findById(any())).thenReturn(Optional.empty());
        when(gateway.instrumentByFigi("FIGI1")).thenReturn(Optional.of(
                com.traderbro.core.domain.Instrument.builder().figi("FIGI1").ticker("X").lot(10)
                        .minPriceIncrement(BigDecimal.ONE).tradable(true).build()));

        manager = new OrderManager(gateway, riskGate, orderStore, portfolio, eventSource,
                new KillSwitch(mock(com.traderbro.core.event.TraderEventPublisher.class)),
                new StreamHealth()::lag, Duration.ofSeconds(60), 4, msg -> { },
                mock(com.traderbro.core.event.TraderEventPublisher.class));
    }

    private OrderRequest request() {
        return OrderRequest.builder()
                .figi("FIGI1").side(OrderSide.BUY).type(OrderType.LIMIT)
                .lots(10).limitPrice(new BigDecimal("200")).strategyId("S").build();
    }

    @Test
    void submitsAndPersistsSentState() {
        Order order = manager.submit(request());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SENT);
        assertThat(order.getBrokerOrderId()).isEqualTo("broker-1");
        verify(orderStore).save(any());
        verify(gateway).postOrder(any());
    }

    @Test
    void deduplicatesByClientOrderId() {
        Order existing = Order.builder().id(UUID.randomUUID()).figi("FIGI1")
                .side(OrderSide.BUY).type(OrderType.LIMIT).requestedLots(10)
                .status(OrderStatus.SENT).createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now()).build();
        when(orderStore.findById(existing.getId())).thenReturn(Optional.of(existing));

        OrderRequest dup = OrderRequest.builder().clientOrderId(existing.getId().toString())
                .figi("FIGI1").side(OrderSide.BUY).type(OrderType.LIMIT).lots(10)
                .limitPrice(new BigDecimal("200")).strategyId("S").build();
        Order result = manager.submit(dup);
        assertThat(result).isSameAs(existing);
        verify(gateway, never()).postOrder(any());
    }

    @Test
    void rejectsWhenRiskGateDenies() {
        when(riskGate.evaluate(any())).thenReturn(RiskDecision.deny("KillSwitch", "active"));
        Order order = manager.submit(request());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getReason()).contains("KillSwitch");
        verify(gateway, never()).postOrder(any());
    }

    @Test
    void handlesPartialFillEvent() {
        Order order = manager.submit(request());
        // Manager looks up by brokerOrderId via findOpen()
        when(orderStore.findOpen()).thenReturn(List.of(order));

        onFill.get().accept(new OrderFillEvent("broker-1", 4, new BigDecimal("199.5"),
                OrderStatus.PARTIALLY_FILLED, 10));
        verify(orderStore).update(any());
        // Verify the updated order recorded the partial fill.
        org.mockito.ArgumentCaptor<Order> captor =
                org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderStore, org.mockito.ArgumentMatchers.atLeastOnce()).update(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(o -> {
            assertThat(o.getFilledLots()).isEqualTo(4);
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        });
    }
}