package com.traderbro.data.tbank;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.OrderRequest;
import com.traderbro.core.domain.OrderState;
import com.traderbro.core.domain.Position;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.BrokerGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.PortfolioPosition;
import ru.tinkoff.piapi.contract.v1.PortfolioResponse;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.OrdersService;
import ru.tinkoff.piapi.core.SandboxService;

/**
 * {@link BrokerGateway} backed by the T-Bank Invest API. In sandbox mode all order calls are
 * routed to {@link SandboxService}; in live mode to {@link OrdersService}.
 *
 * <p>NOTE on SDK API: method names follow the T-Bank Java SDK and must be verified against the
 * pinned version. Each uncertain call is marked {@code // TODO: verify API}.
 */
@Slf4j
public class TBankBrokerGateway implements BrokerGateway {

    private final InvestApi api;
    private final boolean sandbox;
    private final long configuredAccountId;
    private final int moneyScale;
    private volatile long resolvedAccountId = -1;

    public TBankBrokerGateway(InvestApi api, boolean sandbox, long accountId, int moneyScale) {
        this.api = api;
        this.sandbox = sandbox;
        this.configuredAccountId = accountId;
        this.moneyScale = moneyScale;
    }

    /** Resolves the trading account id, opening a sandbox account on first use when needed. */
    private long accountId() {
        if (resolvedAccountId >= 0) {
            return resolvedAccountId;
        }
        if (configuredAccountId > 0) {
            resolvedAccountId = configuredAccountId;
            return resolvedAccountId;
        }
        if (sandbox) {
            // TODO: verify API — openSandboxAccount() returns account id (long).
            resolvedAccountId = sandboxService().openSandboxAccount();
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
        // TODO: verify API — postOrder overloads and return type (PostOrderResponse).
        ru.tinkoff.piapi.contract.v1.PostOrderResponse resp;
        if (sandbox) {
            resp = sandboxService().postOrder(req.getFigi(), req.getLots(), req.getLimitPrice(),
                    direction, accountId(), toSdkType(req.getType()), req.getClientOrderId());
        } else {
            resp = api.getOrdersService().postOrder(req.getFigi(), req.getLots(), req.getLimitPrice(),
                    direction, accountId(), toSdkType(req.getType()), req.getClientOrderId());
        }
        return new BrokerGateway.OrderResponse(resp.getOrderId(),
                resp.getExecutionReportStatus().name(), null);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        // TODO: verify API — cancelOrder signature.
        if (sandbox) {
            sandboxService().cancelOrder(accountId(), brokerOrderId);
        } else {
            api.getOrdersService().cancelOrder(accountId(), brokerOrderId);
        }
    }

    @Override
    public List<Position> getPositions() {
        // TODO: verify API — getPortfolio returns PortfolioResponse.
        PortfolioResponse portfolio = sandbox
                ? sandboxService().getPortfolio(accountId())
                : api.getOperationsService().getPortfolio(accountId());
        List<Position> out = new ArrayList<>();
        for (PortfolioPosition p : portfolio.getPositionsList()) {
            if (p.getQuantity().longValue() == 0) {
                continue;
            }
            out.add(Position.builder()
                    .figi(p.getFigi())
                    .lots(Math.abs(p.getQuantity().longValue()))
                    .shares(Math.abs(p.getQuantity().longValue()))
                    .averagePrice(fromMoney(p.getAveragePositionPrice()))
                    .currentPrice(fromMoney(p.getCurrentPrice()))
                    .build());
        }
        return out;
    }

    @Override
    public List<OrderState> getOpenOrders() {
        // TODO: verify API — getOrders returns List<OrderState>.
        List<ru.tinkoff.piapi.contract.v1.OrderState> states = sandbox
                ? sandboxService().getOrders(accountId())
                : api.getOrdersService().getOrders(accountId());
        List<OrderState> out = new ArrayList<>();
        for (ru.tinkoff.piapi.contract.v1.OrderState s : states) {
            out.add(OrderState.builder()
                    .brokerOrderId(s.getOrderId())
                    .figi(s.getFigi())
                    .status(mapStatus(s.getExecutionReportStatus()))
                    .filledLots(s.getLotsRequested() - s.getLotsRest())
                    .avgFillPrice(fromQuotation(s.getAveragePositionPrice()))
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
        // TODO: verify API — SandboxService.payIn signature.
        MoneyValue mv = MoneyValue.newBuilder().setUnits(amount.longValue())
                .setNano(amount.remainder(BigDecimal.ONE).movePointRight(9).intValue()).build();
        sandboxService().payIn(accountId(), mv);
        return true;
    }

    @Override
    public Optional<Instrument> instrumentByFigi(String figi) {
        // TODO: verify API — InstrumentsService.getShareByFigi returns Share.
        return api.getInstrumentsService().getShareByFigi(figi)
                .map(s -> new com.traderbro.data.mapper.SdkInstrumentMapper(moneyScale).toInstrument(s));
    }

    private SandboxService sandboxService() {
        return api.getSandboxService();
    }

    private BigDecimal fromMoney(MoneyValue m) {
        if (m == null || (m.getUnits() == 0 && m.getNano() == 0)) {
            return BigDecimal.ZERO;
        }
        return com.traderbro.data.mapper.QuotationMapper.toBigDecimal(m, moneyScale);
    }

    private BigDecimal fromQuotation(ru.tinkoff.piapi.contract.v1.Quotation q) {
        return com.traderbro.data.mapper.QuotationMapper.toBigDecimal(q, moneyScale);
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