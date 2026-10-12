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
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Money;
import ru.tinkoff.piapi.core.models.Portfolio;

/**
 * {@link BrokerGateway} backed by the T-Bank Invest API (java-sdk-core 1.36). In sandbox mode
 * all order calls are routed to {@link ru.tinkoff.piapi.core.SandboxService}; in live mode to
 * {@link ru.tinkoff.piapi.core.OrdersService}. Account id is a {@code String}.
 */
@Slf4j
public class TBankBrokerGateway implements BrokerGateway {

    private final InvestApi api;
    private final boolean sandbox;
    private final String configuredAccountId;
    private final int moneyScale;
    private volatile String resolvedAccountId;

    public TBankBrokerGateway(InvestApi api, boolean sandbox, String accountId, int moneyScale) {
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
            resolvedAccountId = api.getSandboxService().openAccountSync();
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
        ru.tinkoff.piapi.contract.v1.PostOrderResponse resp;
        if (sandbox) {
            resp = api.getSandboxService().postOrderSync(req.getFigi(), req.getLots(), price,
                    direction, accountId(), toSdkType(req.getType()), req.getClientOrderId());
        } else {
            resp = api.getOrdersService().postOrderSync(req.getFigi(), req.getLots(), price,
                    direction, accountId(), toSdkType(req.getType()), req.getClientOrderId());
        }
        return new BrokerGateway.OrderResponse(resp.getOrderId(),
                resp.getExecutionReportStatus().name(), null);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        if (sandbox) {
            api.getSandboxService().cancelOrderSync(accountId(), brokerOrderId);
        } else {
            api.getOrdersService().cancelOrderSync(accountId(), brokerOrderId);
        }
    }

    @Override
    public List<Position> getPositions() {
        Portfolio portfolio = sandbox
                ? Portfolio.fromResponse(api.getSandboxService().getPortfolioSync(accountId()))
                : api.getOperationsService().getPortfolioSync(accountId());
        List<Position> out = new ArrayList<>();
        for (ru.tinkoff.piapi.core.models.Position p : portfolio.getPositions()) {
            long shares = p.getQuantity().abs().longValue();
            if (shares == 0) {
                continue;
            }
            out.add(Position.builder()
                    .figi(p.getFigi())
                    .lots(p.getQuantityLots().abs().longValue())
                    .shares(shares)
                    .averagePrice(money(p.getAveragePositionPrice()))
                    .currentPrice(money(p.getCurrentPrice()))
                    .build());
        }
        return out;
    }

    @Override
    public List<OrderState> getOpenOrders() {
        List<ru.tinkoff.piapi.contract.v1.OrderState> states = sandbox
                ? api.getSandboxService().getOrdersSync(accountId())
                : api.getOrdersService().getOrdersSync(accountId());
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
        api.getSandboxService().payInSync(accountId(), mv);
        return true;
    }

    @Override
    public Optional<Instrument> instrumentByFigi(String figi) {
        Share s = api.getInstrumentsService().getShareByFigiSync(figi);
        if (s.getFigi() == null || s.getFigi().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new com.traderbro.data.mapper.SdkInstrumentMapper(moneyScale).toInstrument(s));
    }

    private BigDecimal fromMoney(MoneyValue m) {
        if (m == null || (m.getUnits() == 0 && m.getNano() == 0)) {
            return BigDecimal.ZERO;
        }
        return QuotationMapper.toBigDecimal(m, moneyScale);
    }

    private BigDecimal money(Money m) {
        return m == null ? BigDecimal.ZERO : m.getValue();
    }

    private BigDecimal fromQuotation(Quotation q) {
        if (q == null || (q.getUnits() == 0 && q.getNano() == 0)) {
            return BigDecimal.ZERO;
        }
        return QuotationMapper.toBigDecimal(q, moneyScale);
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