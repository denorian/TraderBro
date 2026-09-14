package com.traderbro.api.config;

import com.traderbro.api.service.BacktestService;
import com.traderbro.api.service.PortfolioService;
import com.traderbro.core.backtest.BacktestRunner;
import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.OrderEventSource;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.core.risk.DataFreshnessRule;
import com.traderbro.core.risk.DailyLossLimitRule;
import com.traderbro.core.risk.ExposureLimitRule;
import com.traderbro.core.risk.FuturesExpiryLockRule;
import com.traderbro.core.risk.KillSwitchRule;
import com.traderbro.core.risk.OrderRateLimitRule;
import com.traderbro.core.risk.PositionLimitRule;
import com.traderbro.core.risk.RiskConfig;
import com.traderbro.core.risk.RiskGate;
import com.traderbro.core.risk.RiskRule;
import com.traderbro.core.risk.TradingWindowRule;
import com.traderbro.core.strategies.BollingerMeanReversionStrategy;
import com.traderbro.core.strategies.DonchianBreakoutStrategy;
import com.traderbro.core.strategies.EmaMomentumStrategy;
import com.traderbro.core.strategies.NoOpSignalFilter;
import com.traderbro.core.strategies.PositionSizer;
import com.traderbro.core.strategies.SmaCrossStrategy;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.data.mapper.SdkBarMapper;
import com.traderbro.data.mapper.SdkInstrumentMapper;
import com.traderbro.data.tbank.HistoryLoadConfig;
import com.traderbro.data.tbank.HistoryLoader;
import com.traderbro.data.tbank.RetryPolicy;
import com.traderbro.data.tbank.TBankBrokerGateway;
import com.traderbro.data.tbank.TBankMarketDataProvider;
import com.traderbro.data.tbank.TBankOrderEventSource;
import com.traderbro.execution.killswitch.KillSwitch;
import com.traderbro.execution.order.OrderManager;
import com.traderbro.execution.reconcile.Reconciler;
import com.traderbro.execution.state.StateRecovery;
import com.traderbro.notify.NotificationService;
import com.traderbro.notify.NotificationStore;
import com.traderbro.scheduler.FuturesRolloverWatcher;
import com.traderbro.scheduler.InstrumentRefreshScheduler;
import com.traderbro.scheduler.PortfolioSnapshotScheduler;
import com.traderbro.scheduler.ReconciliationScheduler;
import com.traderbro.scheduler.SignalEngine;
import com.traderbro.scheduler.StrategyReloadScheduler;
import com.traderbro.scheduler.StreamManager;
import com.traderbro.storage.AuditEventRepository;
import com.traderbro.storage.BacktestRunRepository;
import com.traderbro.storage.InstrumentRepository;
import com.traderbro.storage.PortfolioSnapshotRepository;
import com.traderbro.storage.SignalRepository;
import com.traderbro.storage.StrategyConfigRepository;
import com.traderbro.storage.TradeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.tinkoff.piapi.core.InvestApi;

/**
 * Composition root: wires the domain, data, execution and scheduler beans together and
 * enforces the startup safety checks (live-trading ACK, strategy seeding, state recovery).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, TBankProperties.class,
        RiskProperties.class, ExecutionProperties.class, FuturesProperties.class,
        com.traderbro.notify.telegram.TelegramProperties.class})
public class ApplicationConfig {

    // ------------------------------------------------------------------ T-Bank SDK
    @Bean
    public InvestApi investApi(AppProperties app, TBankProperties tbank) {
        String token = System.getenv(tbank.getTokenEnv());
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing API token in env " + tbank.getTokenEnv());
        }
        if (!app.isSandbox() && !"I_UNDERSTAND_RISK".equals(System.getenv("TRADING_LIVE_ACK"))) {
            throw new IllegalStateException(
                    "Live trading requires TRADING_LIVE_ACK=I_UNDERSTAND_RISK in env");
        }
        // TODO: verify SDK factory API and target-endpoint override on the pinned version.
        return app.isSandbox() ? InvestApi.createSandbox(token) : InvestApi.create(token);
    }

    @Bean
    public SdkBarMapper sdkBarMapper(AppProperties app) {
        return new SdkBarMapper(app.getMoneyScale());
    }

    @Bean
    public SdkInstrumentMapper sdkInstrumentMapper(AppProperties app) {
        return new SdkInstrumentMapper(app.getMoneyScale());
    }

    @Bean
    public RetryPolicy retryPolicy(TBankProperties tbank) {
        return new RetryPolicy(tbank.getRetry().getMaxAttempts(),
                tbank.getRetry().getInitialBackoff(), tbank.getTimeout());
    }

    @Bean
    public MarketDataProvider marketDataProvider(InvestApi api, SdkBarMapper barMapper,
                                                 SdkInstrumentMapper instMapper, AppProperties app,
                                                 RetryPolicy retryPolicy) {
        return new TBankMarketDataProvider(api, barMapper, instMapper, 500, retryPolicy);
    }

    @Bean
    public BrokerGateway brokerGateway(InvestApi api, AppProperties app) {
        return new TBankBrokerGateway(api, app.isSandbox(), 0, app.getMoneyScale());
    }

    @Bean
    public OrderEventSource orderEventSource(InvestApi api, AppProperties app) {
        return new TBankOrderEventSource(api, app.getMoneyScale());
    }

    // ------------------------------------------------------------------ shared state
    @Bean
    public StreamHealth streamHealth() {
        return new StreamHealth();
    }

    @Bean
    public Consumer<String> auditSink(AuditEventRepository repo) {
        return repo.sink("app");
    }

    // ------------------------------------------------------------------ risk
    @Bean
    public RiskConfig riskConfig(RiskProperties props, FuturesProperties futures) {
        java.math.BigDecimal marginPct = java.math.BigDecimal.valueOf(props.getFutures().getMaxMarginPct())
                .movePointLeft(2);
        return new RiskConfig(props.getDailyLossLimitPct(), props.getPositionLimitPct(),
                props.getExposureLimitPct(), props.getMaxOrderRatePerMinute(),
                props.getTradingWindowStart(), props.getTradingWindowEnd(),
                props.isAllowWeekendTrading(), props.getMaxStreamLag(),
                java.time.ZoneId.of(props.getTradingZone()), 4,
                parseWindows(props.getTradingWindows().getFutures()),
                marginPct, futures.getNoNewPositionsDaysBeforeExpiry());
    }

    private static java.util.List<RiskConfig.TradingWindow> parseWindows(String spec) {
        if (spec == null || spec.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(spec.split(","))
                .map(part -> {
                    String[] bounds = part.split("-");
                    if (bounds.length != 2) {
                        throw new IllegalArgumentException("bad trading window: " + part);
                    }
                    return new RiskConfig.TradingWindow(
                            java.time.LocalTime.parse(bounds[0].trim()),
                            java.time.LocalTime.parse(bounds[1].trim()));
                })
                .toList();
    }

    @Bean
    public List<RiskRule> riskRules(RiskConfig rc) {
        return List.of(new KillSwitchRule(), new DailyLossLimitRule(rc),
                new PositionLimitRule(rc), new ExposureLimitRule(rc),
                new OrderRateLimitRule(rc), new FuturesExpiryLockRule(rc),
                new TradingWindowRule(rc), new DataFreshnessRule(rc));
    }

    @Bean
    public RiskGate riskGate(List<RiskRule> riskRules, Consumer<String> auditSink) {
        return new RiskGate(riskRules, auditSink);
    }

    // ------------------------------------------------------------------ strategies
    @Bean
    public PositionSizer positionSizer(AppProperties app) {
        return new PositionSizer(app.getMoneyScale());
    }

    @Bean
    public List<TradingStrategy> tradingStrategies(AppProperties app) {
        return List.of(new SmaCrossStrategy(app.getMoneyScale()),
                new EmaMomentumStrategy(app.getMoneyScale()),
                new BollingerMeanReversionStrategy(app.getMoneyScale()),
                new DonchianBreakoutStrategy(app.getMoneyScale()));
    }

    @Bean
    public NoOpSignalFilter noOpSignalFilter() {
        return new NoOpSignalFilter();
    }

    @Bean
    public StrategyRegistry strategyRegistry(StrategyConfigProvider provider,
                                             List<TradingStrategy> strategies,
                                             NoOpSignalFilter filter, Consumer<String> auditSink) {
        StrategyRegistry registry = new StrategyRegistry(provider, filter, auditSink);
        strategies.forEach(registry::register);
        return registry;
    }

    // ------------------------------------------------------------------ notification
    @Bean
    public TraderEventPublisher traderEventPublisher(TelegramProperties props,
                                                     NotificationStore store) {
        String token = System.getenv(props.getBotTokenEnv());
        String chatId = System.getenv(props.getChatIdEnv());
        boolean enabled = props.isEnabled() && token != null && !token.isBlank()
                && chatId != null && !chatId.isBlank();
        return new NotificationService(enabled, props.getMinLevel(), store);
    }

    // ------------------------------------------------------------------ execution
    @Bean
    public KillSwitch killSwitch(TraderEventPublisher publisher) {
        return new KillSwitch(publisher);
    }

    @Bean
    public OrderManager orderManager(BrokerGateway gateway, RiskGate riskGate,
                                     OrderStore orderStore, PortfolioProvider portfolioProvider,
                                     OrderEventSource eventSource, KillSwitch killSwitch,
                                     StreamHealth streamHealth, ExecutionProperties props,
                                     AppProperties app, Consumer<String> auditSink,
                                     TraderEventPublisher publisher) {
        return new OrderManager(gateway, riskGate, orderStore, portfolioProvider, eventSource,
                killSwitch, streamHealth::lag, props.getOrderTtl(), app.getMoneyScale(), auditSink,
                publisher);
    }

    @Bean
    public StateRecovery stateRecovery(BrokerGateway gateway, OrderStore orderStore,
                                       TradeRepository ledger) {
        return new StateRecovery(gateway, orderStore, ledger);
    }

    @Bean
    public Reconciler reconciler(BrokerGateway gateway, TradeRepository ledger,
                                 OrderStore orderStore, KillSwitch killSwitch,
                                 ExecutionProperties props, Consumer<String> alertSink,
                                 TraderEventPublisher publisher) {
        return new Reconciler(gateway, ledger, orderStore, killSwitch,
                props.isAutoKillOnReconcileDiscrepancy(), alertSink, publisher);
    }

    @Bean
    public PortfolioProvider portfolioProvider(BrokerGateway gateway, AppProperties app) {
        return new PortfolioService(gateway, app.getMoneyScale());
    }

    // ------------------------------------------------------------------ signal engine + stream
    @Bean
    public SignalEngine signalEngine(StrategyRegistry registry, BarStore barStore,
                                     InstrumentRepository instruments, PositionSizer sizer,
                                     OrderManager orderManager, StateRecovery recovery,
                                     SignalRepository signalRepo, PortfolioProvider portfolio,
                                     AppProperties app, TraderEventPublisher publisher) {
        return new SignalEngine(registry, barStore, instruments, sizer, orderManager,
                recovery, signalRepo, portfolio, app.getMaxBarsInMemory(), publisher);
    }

    @Bean
    public StreamManager streamManager(MarketDataProvider marketData, BarStore barStore,
                                       InstrumentRepository instruments, SignalEngine engine,
                                       KillSwitch killSwitch, AppProperties app,
                                       RiskProperties risk, Consumer<String> auditSink,
                                       StreamHealth health, TraderEventPublisher publisher) {
        return new StreamManager(marketData, barStore, instruments, engine, killSwitch,
                CandleInterval.FIFTEEN_MIN, risk.getMaxStreamLag(), auditSink, health, publisher);
    }

    // ------------------------------------------------------------------ backtest
    @Bean
    public BacktestRunner backtestRunner(AppProperties app) {
        return new BacktestRunner(app.getMoneyScale());
    }

    @Bean
    public BacktestService backtestService(BacktestRunner runner, BarStore barStore,
                                           InstrumentRepository instruments,
                                           BacktestRunRepository runRepo, AppProperties app,
                                           TraderEventPublisher publisher) {
        return new BacktestService(runner, barStore, instruments, runRepo,
                System.getenv().getOrDefault("BUILD_GIT_HASH", "dev"), "reports",
                app.getMoneyScale(), publisher);
    }

    // ------------------------------------------------------------------ schedulers
    @Bean
    public ReconciliationScheduler reconciliationScheduler(Reconciler reconciler) {
        return new ReconciliationScheduler(reconciler);
    }

    @Bean
    public StrategyReloadScheduler strategyReloadScheduler(StrategyRegistry registry) {
        return new StrategyReloadScheduler(registry);
    }

    @Bean
    public InstrumentRefreshScheduler instrumentRefreshScheduler(MarketDataProvider md,
                                                                 InstrumentRepository repo,
                                                                 AppProperties app) {
        return new InstrumentRefreshScheduler(md, repo, List.of("SBER", "GAZP", "LKOH", "YDEX", "VTBR"));
    }

    @Bean
    public PortfolioSnapshotScheduler portfolioSnapshotScheduler(PortfolioProvider provider,
                                                                 PortfolioSnapshotRepository repo) {
        return new PortfolioSnapshotScheduler(provider, repo);
    }

    @Bean
    public FuturesRolloverWatcher futuresRolloverWatcher(MarketDataProvider md,
                                                         InstrumentRepository instruments,
                                                         FuturesProperties props,
                                                         TraderEventPublisher publisher,
                                                         Consumer<String> auditSink) {
        return new FuturesRolloverWatcher(md, instruments, props, publisher, auditSink);
    }

    @Bean
    public LifecycleNotifier lifecycleNotifier(TraderEventPublisher publisher, AppProperties app) {
        return new LifecycleNotifier(publisher, app);
    }

    // ------------------------------------------------------------------ history loader
    @Bean
    public HistoryLoadConfig historyLoadConfig(AppProperties app) {
        return new HistoryLoadConfig(app.getHistory().isLoadOnStartup(),
                List.of("SBER", "GAZP", "LKOH", "YDEX", "VTBR"),
                app.getHistory().getDailyYears(), app.getHistory().getIntradayDays(),
                CandleInterval.valueOf(app.getHistory().getIntradayInterval()),
                List.of(CandleInterval.ONE_HOUR));
    }

    @Bean
    public HistoryLoader historyLoader(MarketDataProvider md, BarStore barStore,
                                       HistoryLoadConfig cfg, InstrumentRepository instruments) {
        return new HistoryLoader(md, barStore, cfg, instruments::findByTickerIn);
    }

    // ------------------------------------------------------------------ startup sequence
    @Bean
    public ApplicationRunner startupRunner(StateRecovery recovery, StrategyRegistry registry,
                                           StrategyConfigRepository configRepo,
                                           KillSwitch killSwitch, OrderManager orderManager,
                                           ExecutionProperties props, StreamManager streamManager) {
        return args -> {
            seedStrategies(configRepo);
            registry.reload();
            recovery.recover();
            // Wire the kill-switch side-effects now that OrderManager exists.
            killSwitch.setOnActivate(reason -> {
                orderManager.cancelAll();
                if (props.isLiquidateOnKill()) {
                    // TODO: stage-2 — liquidate open positions through OrderManager.
                    // Not implemented in stage 1 to keep the invariant simple and safe.
                }
            });
            killSwitch.setOnDeactivate(() -> { });
            streamManager.start();
        };
    }

    private void seedStrategies(StrategyConfigRepository repo) {
        repo.upsert(StrategyConfig.builder().id(SmaCrossStrategy.ID).enabled(true)
                .params(Map.of("fast", "20", "slow", "100")).orderType(OrderType.LIMIT)
                .maxPositionPct(new BigDecimal("0.10")).interval(CandleInterval.ONE_DAY).version(1).build());
        repo.upsert(StrategyConfig.builder().id(EmaMomentumStrategy.ID).enabled(true)
                .params(Map.of("fastEm", "12", "slowEm", "26", "rsiPeriod", "14",
                        "rsiMin", "30", "rsiMax", "70", "atrPeriod", "14", "atrMult", "2"))
                .orderType(OrderType.LIMIT).maxPositionPct(new BigDecimal("0.10"))
                .interval(CandleInterval.FIFTEEN_MIN).version(1).build());
    }
}