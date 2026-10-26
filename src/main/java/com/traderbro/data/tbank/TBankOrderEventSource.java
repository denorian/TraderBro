package com.traderbro.data.tbank;

import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderFillEvent;
import com.traderbro.data.mapper.QuotationMapper;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.OrderTrade;
import ru.tinkoff.piapi.contract.v1.TradesStreamRequest;
import ru.tinkoff.piapi.contract.v1.TradesStreamResponse;
import ru.ttech.piapi.core.connector.resilience.ResilienceServerSideStreamWrapper;
import ru.ttech.piapi.core.connector.resilience.ResilienceServerSideStreamWrapperConfiguration;
import ru.ttech.piapi.core.impl.orders.TradeStreamWrapperConfiguration;

/**
 * Bridges the broker order stream to {@link OrderFillEvent}s so {@code OrderManager} can
 * advance its state machine on partial/final fills without depending on the SDK. Uses the
 * SDK's resilience server-side trades stream, which auto-reconnects and resends the last
 * subscription (empty accounts = all).
 */
@Slf4j
public class TBankOrderEventSource implements OrderEventSource {

    private final TBankApi api;
    private final int moneyScale;
    private final AtomicReference<Consumer<OrderFillEvent>> listener = new AtomicReference<>();

    public TBankOrderEventSource(TBankApi api, int moneyScale) {
        this.api = api;
        this.moneyScale = moneyScale;
    }

    @Override
    public synchronized void subscribe(Consumer<OrderFillEvent> onFill) {
        if (!listener.compareAndSet(null, onFill)) {
            log.warn("order stream already subscribed; replacing listener");
            listener.set(onFill);
        }
        ResilienceServerSideStreamWrapperConfiguration<TradesStreamRequest, TradesStreamResponse> config =
                TradeStreamWrapperConfiguration.builder(api.scheduledExecutor())
                        .addOnResponseListener(this::onResponse)
                        .addOnConnectListener(() -> log.info("order trades stream connected"))
                        .build();
        ResilienceServerSideStreamWrapper<TradesStreamRequest, TradesStreamResponse> stream =
                api.streamFactory().newResilienceServerSideStream(config);
        // Subscribes to trades for all accounts (empty request = all).
        stream.subscribe(TradesStreamRequest.getDefaultInstance());
        log.info("order stream subscribed");
    }

    private void onResponse(TradesStreamResponse response) {
        if (!response.hasOrderTrades()) {
            return;
        }
        var orderTrades = response.getOrderTrades();
        String orderId = orderTrades.getOrderId();
        for (OrderTrade t : orderTrades.getTradesList()) {
            BigDecimal price = QuotationMapper.toBigDecimal(t.getPrice(), moneyScale);
            long qty = t.getQuantity();
            Consumer<OrderFillEvent> cb = listener.get();
            if (cb != null) {
                cb.accept(new OrderFillEvent(orderId, qty, price,
                        OrderStatus.PARTIALLY_FILLED, qty));
            }
        }
    }
}