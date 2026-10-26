package com.traderbro.data.tbank;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.OrderRequest;
import com.traderbro.core.domain.OrderState;
import com.traderbro.core.domain.Position;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.data.mapper.QuotationMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.PortfolioPosition;
import ru.tinkoff.piapi.contract.v1.PortfolioResponse;
import ru.tinkoff.piapi.contract.v1.Quotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BrokerGateway} backed by the T-Bank Invest API (java-sdk-core 1.51). Sandbox mode is a
 * {@code sandbox.enabled} flag on the connection, so all order calls route through
 * {@link TBankApi} which picks the sandbox/live endpoint. Account id is a {@code String}.
 */
@Slf4j
public class TBankBrokerGateway implements BrokerGateway {

    private final TBankApi api;
    private final boolean sandbox;
    private final String configuredAccountId;
    private final int moneyScale;
    private volatile String resolvedAccountId;

    /** figi → {ticker, name} for instrument names on positions; instruments are immutable so a
     * tiny in-memory cache avoids a T-Bank lookup per poll. Unresolvable figis are not cached
     * (absent key) so a later successful lookup is still possible. */
    private final Map<String, String[]> instrumentNames = new ConcurrentHashMap<>();

    public TBankBrokerGateway(TBankApi api, boolean sandbox, String accountId, int moneyScale) {
        this.api = api;
        this.sandbox = sandbox;
        this.configuredAccountId = accountId;
        this.moneyScale = moneyScale;
    }

    /** Resolves the trading account id, opening a sandbox account on first use when needed. */
    private String accountId() {
        if (resolvedAccountId != null) {
            return resolvedAccountId;
        }
        if (configuredAccountId != null && !configuredAccountId.isBlank()) {
            resolvedAccountId = configuredAccountId;
            return resolvedAccountId;
        }
        if (sandbox) {
            resolvedAccountId = api.openAccountSync();
            log.info("opened sandbox account id={}", resolvedAccountId);
            return resolvedAccountId;
        }
        throw new IllegalStateException("no account id configured for live trading");
    }

    @Override
    public BrokerGateway.OrderResponse postOrder(OrderRequest req) {
        ru.tinkoff.piapi.contract.v1.OrderDirection direction =
                req.getSide() == OrderSide.BUY
                        ? ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_BUY
                        : ru.tinkoff.piapi.contract.v1.OrderDirection.ORDER_DIRECTION_SELL;
        Quotation price = req.getLimitPrice() == null ? Quotation.getDefaultInstance()
                : toQuotation(req.getLimitPrice());
        ru.tinkoff.piapi.contract.v1.PostOrderResponse resp = api.postOrderSync(
                req.getFigi(), req.getLots(), price, direction, accountId(),
                toSdkType(req.getType()), req.getClientOrderId());
        return new BrokerGateway.OrderResponse(resp.getOrderId(),
                resp.getExecutionReportStatus().name(), null);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        api.cancelOrderSync(accountId(), brokerOrderId);
    }

    @Override
    public List<Position> getPositions() {
        PortfolioResponse portfolio = api.getPortfolioSync(accountId());
        List<Position> out = new ArrayList<>();
        for (PortfolioPosition p : portfolio.getPositionsList()) {
            long shares = Math.abs(p.getQuantity().getUnits());
            if (shares == 0) {
                continue;
            }
            out.add(Position.builder()
                    .figi(p.getFigi())
                    .lots(Math.abs(p.getQuantityLots().getUnits()))
                    .shares(shares)
                    .averagePrice(fromMoney(p.getAveragePositionPrice()))
                    .currentPrice(fromMoney(p.getCurrentPrice()))
                    .ticker(tickerOf(p.getFigi()))
                    .name(nameOf(p.getFigi()))
                    .build());
        }
        return out;
    }

    /** Returns the resolved {ticker, name} pair for a figi (cached), or null components if the
     * instrument cannot be resolved (e.g. money/currency positions). */
    private String[] names(String figi) {
        String[] cached = instrumentNames.get(figi);
        if (cached != null) {
            return cached;
        }
        try {
            var sec = api.getInstrumentByFigiSync(figi);
            String[] names = { sec.getTicker(), sec.getName() };
            instrumentNames.put(figi, names);
            return names;
        } catch (Exception e) {
            log.debug("cannot resolve instrument name for figi {}: {}", figi, e.getMessage());
            return null;
        }
    }

    private String tickerOf(String figi) {
        String[] names = names(figi);
        return names == null ? null : names[0];
    }

    private String nameOf(String figi) {
        String[] names = names(figi);
        return names == null ? null : names[1];
    }

    @Override
    public List<OrderState> getOpenOrders() {
        List<ru.tinkoff.piapi.contract.v1.OrderState> states = api.getOrdersSync(accountId());
        List<OrderState> out = new ArrayList<>();
        for (ru.tinkoff.piapi.contract.v1.OrderState s : states) {
            out.add(OrderState.builder()
                    .brokerOrderId(s.getOrderId())
                    .figi(s.getFigi())
                    .status(mapStatus(s.getExecutionReportStatus()))
                    .filledLots(s.getLotsExecuted())
                    .avgFillPrice(fromMoney(s.getAveragePositionPrice()))
                    .build());
        }
        return out;
    }

    @Override
    public boolean creditSandbox(BigDecimal amount) {
        if (!sandbox) {
            log.warn("creditSandbox ignored in live mode");
            return false;
        }
        MoneyValue mv = MoneyValue.newBuilder().setCurrency("rub")
                .setUnits(amount.longValue())
                .setNano(amount.remainder(BigDecimal.ONE).movePointRight(9).intValue())
                .build();
        api.payInSync(accountId(), mv);
        return true;
    }

    @Override
    public Optional<Instrument> instrumentByFigi(String figi) {
        com.traderbro.data.mapper.SdkInstrumentMapper m =
                new com.traderbro.data.mapper.SdkInstrumentMapper(moneyScale);
        ru.tinkoff.piapi.contract.v1.Share s = api.getShareByFigiSync(figi);
        if (s.getFigi() != null && !s.getFigi().isBlank()) {
            return Optional.of(m.toInstrument(s));
        }
        // Not a share — fall back to futures so the execution path can resolve a futures FIGI.
        ru.tinkoff.piapi.contract.v1.Future f = api.getFutureByFigiSync(figi);
        if (f.getFigi() != null && !f.getFigi().isBlank()) {
            return Optional.of(m.toInstrument(f));
        }
        return Optional.empty();
    }

    private BigDecimal fromMoney(MoneyValue m) {
        if (m == null || (m.getUnits() == 0 && m.getNano() == 0)) {
            return BigDecimal.ZERO;
        }
        return QuotationMapper.toBigDecimal(m, moneyScale);
    }

    private static Quotation toQuotation(BigDecimal v) {
        long units = v.longValue();
        int nano = v.subtract(BigDecimal.valueOf(units)).movePointRight(9).intValue();
        return Quotation.newBuilder().setUnits(units).setNano(nano).build();
    }

    private static ru.tinkoff.piapi.contract.v1.OrderType toSdkType(OrderType type) {
        return switch (type) {
            case LIMIT -> ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_LIMIT;
            case MARKET -> ru.tinkoff.piapi.contract.v1.OrderType.ORDER_TYPE_MARKET;
        };
    }

    private static OrderStatus mapStatus(ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus s) {
        return switch (s) {
            case EXECUTION_REPORT_STATUS_FILL -> OrderStatus.FILLED;
            case EXECUTION_REPORT_STATUS_REJECTED -> OrderStatus.REJECTED;
            case EXECUTION_REPORT_STATUS_CANCELLED -> OrderStatus.CANCELLED;
            case EXECUTION_REPORT_STATUS_PARTIALLYFILL -> OrderStatus.PARTIALLY_FILLED;
            case EXECUTION_REPORT_STATUS_NEW -> OrderStatus.SENT;
            default -> OrderStatus.SENT;
        };
    }
}