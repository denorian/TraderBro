package com.traderbro.data.tbank;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.CancelOrderRequest;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.FutureResponse;
import ru.tinkoff.piapi.contract.v1.FuturesResponse;
import ru.tinkoff.piapi.contract.v1.GetCandlesRequest;
import ru.tinkoff.piapi.contract.v1.GetCandlesResponse;
import ru.tinkoff.piapi.contract.v1.GetOrdersRequest;
import ru.tinkoff.piapi.contract.v1.GetOrdersResponse;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.Instrument;
import ru.tinkoff.piapi.contract.v1.InstrumentIdType;
import ru.tinkoff.piapi.contract.v1.InstrumentRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.InstrumentStatus;
import ru.tinkoff.piapi.contract.v1.MarketDataServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountResponse;
import ru.tinkoff.piapi.contract.v1.OperationsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderState;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.PortfolioRequest;
import ru.tinkoff.piapi.contract.v1.PortfolioResponse;
import ru.tinkoff.piapi.contract.v1.PostOrderRequest;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.SandboxPayInRequest;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.contract.v1.ShareResponse;
import ru.tinkoff.piapi.contract.v1.SharesResponse;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.resilience.ResilienceConfiguration;
import ru.ttech.piapi.core.connector.resilience.ResilienceSyncStubWrapper;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;

/**
 * Thin adapter over the T-Bank Invest API java-sdk-core (1.51). The new SDK dropped the
 * high-level {@code InvestApi} facade and exposes raw gRPC stubs wrapped in
 * {@link ResilienceSyncStubWrapper}; sandbox mode is a {@code sandbox.enabled} flag that
 * switches the target endpoint, not a separate service. Retries/backoff are provided by the
 * SDK's resilience layer instead of a hand-rolled {@link RetryPolicy}.
 */
@Slf4j
public class TBankApi implements AutoCloseable {

    private final boolean sandbox;
    private final ScheduledExecutorService scheduledExecutor;
    private final StreamServiceStubFactory streamFactory;
    private final ResilienceSyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instruments;
    private final ResilienceSyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketData;
    private final ResilienceSyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> orders;
    private final ResilienceSyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operations;
    private final ResilienceSyncStubWrapper<SandboxServiceGrpc.SandboxServiceBlockingStub> sandboxStub;

    public TBankApi(String token, boolean sandbox, int maxAttempts, String target) {
        this.sandbox = sandbox;
        Properties props = new Properties();
        props.setProperty("token", token);
        props.setProperty("sandbox.enabled", String.valueOf(sandbox));
        props.setProperty("connection.retry.max-attempts", String.valueOf(maxAttempts));
        // The SDK defaults ssl.secure-internal=true, which trusts a single bundled Russian root CA
        // (RussianTrustedRootCA.jks) and fails the TLS handshake against the public API (old SDK 1.36
        // used the default JVM trust store). Disable it so the system trust store is used, and pin the
        // configured target host (app default: invest-public-api.tinkoff.ru) like the old SDK did.
        props.setProperty("connection.ssl.secure-internal", "false");
        props.setProperty("target", withPort(target));
        props.setProperty("sandbox.target", withPort("sandbox-" + target));
        ConnectorConfiguration config = ConnectorConfiguration.loadFromProperties(props);
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        ResilienceConfiguration resilience = ResilienceConfiguration.builder(scheduledExecutor, config).build();
        ServiceStubFactory stubFactory = ServiceStubFactory.create(config);
        this.instruments = stubFactory.newResilienceSyncService(InstrumentsServiceGrpc::newBlockingStub, resilience);
        this.marketData = stubFactory.newResilienceSyncService(MarketDataServiceGrpc::newBlockingStub, resilience);
        this.orders = stubFactory.newResilienceSyncService(OrdersServiceGrpc::newBlockingStub, resilience);
        this.operations = stubFactory.newResilienceSyncService(OperationsServiceGrpc::newBlockingStub, resilience);
        this.sandboxStub = stubFactory.newResilienceSyncService(SandboxServiceGrpc::newBlockingStub, resilience);
        this.streamFactory = StreamServiceStubFactory.create(stubFactory);
    }

    // ---------------------------------------------------------------- market data
    public List<HistoricCandle> getCandlesSync(String figi, Instant from, Instant to,
                                               CandleInterval interval) {
        GetCandlesRequest request = GetCandlesRequest.newBuilder()
                .setInstrumentId(figi)
                .setFrom(toProto(from))
                .setTo(toProto(to))
                .setInterval(interval)
                .build();
        GetCandlesResponse response = marketData.callSyncMethod(
                MarketDataServiceGrpc.getGetCandlesMethod(), stub -> stub.getCandles(request));
        return response.getCandlesList();
    }

    // ---------------------------------------------------------------- instruments
    public List<Share> getSharesSync() {
        InstrumentsRequest request = InstrumentsRequest.newBuilder()
                .setInstrumentStatus(InstrumentStatus.INSTRUMENT_STATUS_BASE).build();
        SharesResponse response = instruments.callSyncMethod(
                InstrumentsServiceGrpc.getSharesMethod(), stub -> stub.shares(request));
        return response.getInstrumentsList();
    }

    public List<Future> getFuturesSync() {
        InstrumentsRequest request = InstrumentsRequest.newBuilder()
                .setInstrumentStatus(InstrumentStatus.INSTRUMENT_STATUS_BASE).build();
        FuturesResponse response = instruments.callSyncMethod(
                InstrumentsServiceGrpc.getFuturesMethod(), stub -> stub.futures(request));
        return response.getInstrumentsList();
    }

    public Share getShareByFigiSync(String figi) {
        ShareResponse response = instruments.callSyncMethod(InstrumentsServiceGrpc.getShareByMethod(),
                stub -> stub.shareBy(figiRequest(figi)));
        return response.getInstrument();
    }

    public Future getFutureByFigiSync(String figi) {
        FutureResponse response = instruments.callSyncMethod(InstrumentsServiceGrpc.getFutureByMethod(),
                stub -> stub.futureBy(figiRequest(figi)));
        return response.getInstrument();
    }

    public Instrument getInstrumentByFigiSync(String figi) {
        InstrumentResponse response = instruments.callSyncMethod(
                InstrumentsServiceGrpc.getGetInstrumentByMethod(), stub -> stub.getInstrumentBy(figiRequest(figi)));
        return response.getInstrument();
    }

    // ---------------------------------------------------------------- trading
    public PostOrderResponse postOrderSync(String figi, long lots, Quotation price, OrderDirection direction,
                                           String accountId, OrderType type, String clientOrderId) {
        PostOrderRequest request = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(lots)
                .setPrice(price)
                .setDirection(direction)
                .setAccountId(accountId)
                .setOrderType(type)
                .setOrderId(clientOrderId)
                .build();
        if (sandbox) {
            return sandboxStub.callSyncMethod(SandboxServiceGrpc.getPostSandboxOrderMethod(),
                    stub -> stub.postSandboxOrder(request));
        }
        return orders.callSyncMethod(OrdersServiceGrpc.getPostOrderMethod(), stub -> stub.postOrder(request));
    }

    public void cancelOrderSync(String accountId, String orderId) {
        CancelOrderRequest request = CancelOrderRequest.newBuilder()
                .setAccountId(accountId).setOrderId(orderId).build();
        if (sandbox) {
            sandboxStub.callSyncMethod(SandboxServiceGrpc.getCancelSandboxOrderMethod(),
                    stub -> stub.cancelSandboxOrder(request));
        } else {
            orders.callSyncMethod(OrdersServiceGrpc.getCancelOrderMethod(), stub -> stub.cancelOrder(request));
        }
    }

    public List<OrderState> getOrdersSync(String accountId) {
        GetOrdersRequest request = GetOrdersRequest.newBuilder().setAccountId(accountId).build();
        GetOrdersResponse response = sandbox
                ? sandboxStub.callSyncMethod(SandboxServiceGrpc.getGetSandboxOrdersMethod(),
                        stub -> stub.getSandboxOrders(request))
                : orders.callSyncMethod(OrdersServiceGrpc.getGetOrdersMethod(), stub -> stub.getOrders(request));
        return response.getOrdersList();
    }

    public PortfolioResponse getPortfolioSync(String accountId) {
        PortfolioRequest request = PortfolioRequest.newBuilder().setAccountId(accountId).build();
        return sandbox
                ? sandboxStub.callSyncMethod(SandboxServiceGrpc.getGetSandboxPortfolioMethod(),
                        stub -> stub.getSandboxPortfolio(request))
                : operations.callSyncMethod(OperationsServiceGrpc.getGetPortfolioMethod(),
                        stub -> stub.getPortfolio(request));
    }

    public String openAccountSync() {
        OpenSandboxAccountResponse response = sandboxStub.callSyncMethod(
                SandboxServiceGrpc.getOpenSandboxAccountMethod(),
                stub -> stub.openSandboxAccount(OpenSandboxAccountRequest.getDefaultInstance()));
        return response.getAccountId();
    }

    public void payInSync(String accountId, MoneyValue amount) {
        SandboxPayInRequest request = SandboxPayInRequest.newBuilder()
                .setAccountId(accountId).setAmount(amount).build();
        sandboxStub.callSyncMethod(SandboxServiceGrpc.getSandboxPayInMethod(),
                stub -> stub.sandboxPayIn(request));
    }

    // ---------------------------------------------------------------- accessors
    public StreamServiceStubFactory streamFactory() {
        return streamFactory;
    }

    public ScheduledExecutorService scheduledExecutor() {
        return scheduledExecutor;
    }

    private static InstrumentRequest figiRequest(String figi) {
        return InstrumentRequest.newBuilder()
                .setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI)
                .setId(figi)
                .build();
    }

    private static com.google.protobuf.Timestamp toProto(Instant t) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
    }

    private static String withPort(String host) {
        return host.contains(":") ? host : host + ":443";
    }

    @Override
    public void close() {
        scheduledExecutor.shutdownNow();
    }
}