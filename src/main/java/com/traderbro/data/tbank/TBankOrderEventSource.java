package com.traderbro.data.tbank;

import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderFillEvent;
import com.traderbro.data.mapper.QuotationMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.OrderTrade;
import ru.tinkoff.piapi.contract.v1.TradesStreamResponse;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.stream.OrdersStreamService;
import ru.tinkoff.piapi.core.stream.StreamProcessor;

/**
 * Bridges the broker order stream to {@link OrderFillEvent}s so {@code OrderManager} can
 * advance its state machine on partial/final fills without depending on the SDK.
 */
@Slf4j
public class TBankOrderEventSource implements OrderEventSource {

    private final OrdersStreamService ordersStreamService;
    private final int moneyScale;
    private final AtomicReference<Consumer<OrderFillEvent>> listener = new AtomicReference<>();

    public TBankOrderEventSource(InvestApi api, int moneyScale) {
        this.ordersStreamService = api.getOrdersStreamService();
        this.moneyScale = moneyScale;
    }

    @Override
    public synchronized void subscribe(Consumer<OrderFillEvent> onFill) {
        if (!listener.compareAndSet(null, onFill)) {
            log.warn("order stream already subscribed; replacing listener");
            listener.set(onFill);
        }
        StreamProcessor<TradesStreamResponse> processor = response -> {
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
        };
        // Subscribes to trades for all accounts (empty iterable = all).
        ordersStreamService.subscribeTrades(processor, err ->
                log.warn("order trades stream error: {}", err.getMessage()), List.of());
        log.info("order stream subscribed");
    }
}