package com.traderbro.data.tbank;

import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderFillEvent;
import com.traderbro.data.mapper.QuotationMapper;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.StreamService;

/**
 * Bridges the broker order stream to {@link OrderFillEvent}s so {@code OrderManager} can
 * advance its state machine on partial/final fills without depending on the SDK.
 */
@Slf4j
public class TBankOrderEventSource implements OrderEventSource {

    private final InvestApi api;
    private final int moneyScale;
    private final AtomicReference<Consumer<OrderFillEvent>> listener = new AtomicReference<>();

    public TBankOrderEventSource(InvestApi api, int moneyScale) {
        this.api = api;
        this.moneyScale = moneyScale;
    }

    @Override
    public synchronized void subscribe(Consumer<OrderFillEvent> onFill) {
        if (!listener.compareAndSet(null, onFill)) {
            log.warn("order stream already subscribed; replacing listener");
            listener.set(onFill);
        }
        // TODO: verify API — OrdersStreamService()/getOrdersStream() subscription mechanics.
        StreamService<ru.tinkoff.piapi.contract.v1.StreamTrade> tradeStream =
                api.getOrdersStreamService().getTradesStream();
        tradeStream.subscribe(trade -> {
            var t = trade.getTrade();
            BigDecimal avg = QuotationMapper.toBigDecimal(t.getPrice(), moneyScale);
            OrderFillEvent event = new OrderFillEvent(
                    t.getOrderId(),
                    t.getQuantity(),
                    avg,
                    OrderStatus.PARTIALLY_FILLED,
                    t.getQuantity());
            Consumer<OrderFillEvent> cb = listener.get();
            if (cb != null) {
                cb.accept(event);
            }
        });
        // TODO: verify API — subscribeOrders stream initiation method.
        api.getOrdersStreamService().subscribeOrders(java.util.List.of());
        log.info("order stream subscribed");
    }
}