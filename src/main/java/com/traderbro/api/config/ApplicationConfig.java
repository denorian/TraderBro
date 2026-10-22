package com.traderbro.api.config;

import com.traderbro.api.service.BacktestService;
import com.traderbro.api.service.PortfolioService;
import com.traderbro.core.backtest.BacktestOrchestrator;
import com.traderbro.core.backtest.BacktestRunner;
import com.traderbro.core.backtest.WalkForwardRunner;
import com.traderbro.core.domain.Instrument;
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
import com.traderbro.core.indicators.AngulationDetector;
import com.traderbro.core.indicators.AoTwinPeaksDetector;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.core.indicators.IndicatorSnapshotService;
import com.traderbro.core.strategies.AlligatorAoStrategy;
import com.traderbro.core.strategies.AoDivergenceReversalStrategy;
import com.traderbro.core.strategies.BollingerMeanReversionStrategy;
import com.traderbro.core.strategies.DonchianBreakoutStrategy;
import com.traderbro.core.strategies.EmaMomentumStrategy;
import com.traderbro.core.strategies.NoOpSignalFilter;
import com.traderbro.core.strategies.PositionSizer;
import com.traderbro.core.strategies.SmaCrossStrategy;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.core.williams.WilliamsContextService;
import com.traderbro.core.williams.WilliamsEventDetector;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.data.mapper.SdkBarMapper;
import com.traderbro.data.mapper.SdkInstrumentMapper;
import com.traderbro.data.tbank.HistoryLoadConfig;
import com.traderbro.data.tbank.HistoryLoader;
import com.traderbro.data.tbank.RetryPolicy;
import com.traderbro.data.tbank.TBankBrokerGateway;
import com.traderbro.data.tbank.TBankMarketDataProvider;
import com.traderbro.data.tbank.ApiRateLimiter;
import com.traderbro.data.tbank.TBankOrderEventSource;
import com.traderbro.execution.killswitch.KillSwitch;
import com.traderbro.execution.order.OrderManager;
import com.traderbro.execution.reconcile.Reconciler;
import com.traderbro.execution.state.StateRecovery;
import com.traderbro.notify.LifecycleNotifier;
import com.traderbro.notify.NotificationService;
import com.traderbro.notify.NotificationStore;
import com.traderbro.notify.telegram.TelegramProperties;
import com.traderbro.scheduler.FuturesRolloverWatcher;
import com.traderbro.scheduler.InstrumentRefreshScheduler;
import com.traderbro.scheduler.PortfolioSnapshotScheduler;
import com.traderbro.scheduler.ReconciliationScheduler;
import com.traderbro.scheduler.SignalEngine;
import com.traderbro.scheduler.StrategyReloadScheduler;
import com.traderbro.scheduler.StreamManager;
import com.traderbro.storage.AuditEventRepository;
import com.traderbro.storage.AngulationEventRepository;
import com.traderbro.storage.BacktestRunRepository;
import com.traderbro.storage.DivergenceEventRepository;
import com.traderbro.storage.InstrumentRepository;
import com.traderbro.storage.PortfolioSnapshotRepository;
import com.traderbro.storage.SignalRepository;
import com.traderbro.storage.StrategyConfigRepository;
import com.traderbro.storage.TradeRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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
        IndicatorsProperties.class, BacktestProperties.class, InstrumentsProperties.class,
        WilliamsProperties.class, AoDivergenceProperties.class,
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
                                                 RetryPolicy retryPolicy, TBankProperties tbank) {
        return new TBankMarketDataProvider(api, barMapper, instMapper, 500, retryPolicy,
                new ApiRateLimiter(tbank.getRateLimitPerMin()));
    }

    @Bean
    public BrokerGateway brokerGateway(InvestApi api, AppProperties app) {
        return new TBankBrokerGateway(api, app.isSandbox(), app.getAccountId(), app.getMoneyScale());
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
    public List<TradingStrategy> tradingStrategies(AppProperties app,
                                                   IndicatorFactory indicatorFactory,
                                                   AngulationDetector angulationDetector,
                                                   AoTwinPeaksDetector twinPeaks,
                                                   WilliamsProperties williams,
                                                   AoDivergenceProperties aoDiv) {
        List<TradingStrategy> list = new ArrayList<>(List.of(
                new SmaCrossStrategy(app.getMoneyScale()),
                new EmaMomentumStrategy(app.getMoneyScale()),
                new BollingerMeanReversionStrategy(app.getMoneyScale()),
                new DonchianBreakoutStrategy(app.getMoneyScale()),
                new AlligatorAoStrategy(app.getMoneyScale(), indicatorFactory.getAlligator(),
                        angulationDetector, williams.getAngulation().getThresholdAtrPerBar()),
                new AoDivergenceReversalStrategy(app.getMoneyScale(), twinPeaks,
                        angulationDetector, aoDiv.isAllowShort(), aoDiv.getAtrPeriod(),
                        williams.getAngulation().getLookbackBars())));
        return list;
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

    // ------------------------------------------------------------------ Williams indicators
    @Bean
    public IndicatorFactory indicatorFactory(IndicatorsProperties props) {
        return new IndicatorFactory(
                new IndicatorFactory.AlligatorParams(
                        props.getAlligator().getJawPeriod(), props.getAlligator().getJawShift(),
                        props.getAlligator().getTeethPeriod(), props.getAlligator().getTeethShift(),
                        props.getAlligator().getLipsPeriod(), props.getAlligator().getLipsShift(),
                        props.getAlligator().getAtrPeriod(),
                        props.getAlligator().getEntanglementAtrThreshold()),
                new IndicatorFactory.AoParams(
                        props.getAwesomeOscillator().getFast(),
                        props.getAwesomeOscillator().getSlow()));
    }

    @Bean
    public IndicatorSnapshotService indicatorSnapshotService(IndicatorFactory factory,
                                                             BarStore barStore,
                                                             InstrumentRepository instruments,
                                                             com.traderbro.storage.IndicatorSnapshotRepository snapshotRepo,
                                                             AngulationDetector angulationDetector,
                                                             WilliamsEventDetector williams,
                                                             IndicatorsProperties props,
                                                             AppProperties app) {
        return new IndicatorSnapshotService(factory, barStore, instruments, snapshotRepo,
                angulationDetector, williams, props, app.getMaxBarsInMemory());
    }

    // ------------------------------------------------------------------ Williams event detectors
    @Bean
    public AoTwinPeaksDetector twinPeaksDetector(WilliamsProperties props) {
        return new AoTwinPeaksDetector(props.getDivergence());
    }

    @Bean
    public AngulationDetector angulationDetector(WilliamsProperties props) {
        return new AngulationDetector(props.getAngulation());
    }

    @Bean
    public WilliamsEventDetector williamsEventDetector(IndicatorFactory factory,
                                                       AoTwinPeaksDetector twinPeaks,
                                                       AngulationDetector angulationDetector,
                                                       DivergenceEventRepository divergences,
                                                       AngulationEventRepository angulations,
                                                       InstrumentRepository instruments,
                                                       TraderEventPublisher publisher,
                                                       AppProperties app) {
        return new WilliamsEventDetector(factory, twinPeaks, angulationDetector, divergences,
                angulations, instruments, publisher, app.getMaxBarsInMemory());
    }

    @Bean
    public WilliamsContextService williamsContextService(IndicatorFactory factory,
                                                         AngulationDetector angulationDetector,
                                                         DivergenceEventRepository divergences,
                                                         AngulationEventRepository angulations,
                                                         BarStore barStore, AppProperties app) {
        return new WilliamsContextService(factory, angulationDetector, divergences, angulations,
                barStore, app.getMaxBarsInMemory());
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
                                       TradeRepository ledger, AppProperties app) {
        return new StateRecovery(gateway, orderStore, ledger, app.isObserveOnly());
    }

    @Bean
    public Reconciler reconciler(BrokerGateway gateway, TradeRepository ledger,
                                 OrderStore orderStore, KillSwitch killSwitch,
                                 ExecutionProperties props, Consumer<String> alertSink,
                                 TraderEventPublisher publisher, StateRecovery stateRecovery) {
        return new Reconciler(gateway, ledger, orderStore, killSwitch,
                props.isAutoKillOnReconcileDiscrepancy(), alertSink, publisher, stateRecovery);
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
                                       IndicatorSnapshotService snapshotService,
                                       KillSwitch killSwitch, AppProperties app,
                                       RiskProperties risk, Consumer<String> auditSink,
                                       StreamHealth health, TraderEventPublisher publisher) {
        return new StreamManager(marketData, barStore, instruments, engine, snapshotService,
                killSwitch, CandleInterval.FIFTEEN_MIN, risk.getMaxStreamLag(),
                auditSink, health, publisher);
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

    @Bean
    public WalkForwardRunner walkForwardRunner(BacktestRunner runner) {
        return new WalkForwardRunner(runner);
    }

    @Bean
    public BacktestOrchestrator backtestOrchestrator(BacktestRunner runner,
                                                     WalkForwardRunner walkForwardRunner,
                                                     BarStore barStore,
                                                     InstrumentRepository instruments,
                                                     com.traderbro.storage.BacktestBatchRepository batchRepo,
                                                     StrategyRegistry registry,
                                                     StrategyConfigProvider configProvider,
                                                     TraderEventPublisher publisher,
                                                     BacktestProperties props) {
        return new BacktestOrchestrator(runner, walkForwardRunner, barStore, instruments,
                batchRepo, registry, configProvider, publisher,
                System.getenv().getOrDefault("BUILD_GIT_HASH", "dev"), props);
    }

    @Bean
    public com.traderbro.scheduler.BacktestRetentionScheduler backtestRetentionScheduler(
            com.traderbro.storage.BacktestBatchRepository batchRepo, BacktestProperties props) {
        return new com.traderbro.scheduler.BacktestRetentionScheduler(batchRepo, props);
    }

    // ------------------------------------------------------------------ schedulers
    @Bean
    public ReconciliationScheduler reconciliationScheduler(Reconciler reconciler,
                                                           StateRecovery stateRecovery) {
        return new ReconciliationScheduler(reconciler, stateRecovery);
    }

    @Bean
    public com.traderbro.scheduler.DailySummaryReporter dailySummaryReporter(
            PortfolioProvider portfolioProvider,
            com.traderbro.storage.SignalRepository signalRepository,
            com.traderbro.storage.TradeRepository tradeRepository,
            KillSwitch killSwitch,
            StreamHealth streamHealth,
            TraderEventPublisher publisher) {
        return new com.traderbro.scheduler.DailySummaryReporter(portfolioProvider,
                signalRepository, tradeRepository, killSwitch, streamHealth, publisher);
    }

    @Bean
    public StrategyReloadScheduler strategyReloadScheduler(StrategyRegistry registry) {
        return new StrategyReloadScheduler(registry);
    }

    @Bean
    public InstrumentRefreshScheduler instrumentRefreshScheduler(MarketDataProvider md,
                                                                 InstrumentRepository repo,
                                                                 InstrumentsProperties instruments) {
        return new InstrumentRefreshScheduler(md, repo, instruments.getTickers());
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
    public HistoryLoadConfig historyLoadConfig(AppProperties app, InstrumentsProperties instruments) {
        return new HistoryLoadConfig(app.getHistory().isLoadOnStartup(),
                instruments.getTickers(),
                app.getHistory().getDailyYears(), app.getHistory().getIntradayDays(),
                CandleInterval.valueOf(app.getHistory().getIntradayInterval()),
                List.of(CandleInterval.ONE_HOUR, CandleInterval.FOUR_HOUR));
    }

    @Bean
    public HistoryLoader historyLoader(MarketDataProvider md, BarStore barStore,
                                       HistoryLoadConfig cfg, InstrumentRepository instruments,
                                       FuturesProperties futuresProps) {
        return new HistoryLoader(md, barStore, cfg, tickers -> {
            // Load instruments (shares + all futures for the configured basic assets) from the
            // broker and persist them so streams/signals can start immediately, even before the
            // 08:00 instrument-refresh cron fires.
            List<Instrument> resolved = new ArrayList<>();
            try {
                resolved.addAll(md.findInstruments(tickers));
            } catch (RuntimeException e) {
                resolved.addAll(instruments.findByTickerIn(tickers));
            }
            List<String> basicAssets = futuresProps.getBasicAssets();
            if (basicAssets != null && !basicAssets.isEmpty()) {
                resolved.addAll(md.findAllFutures(basicAssets));
            }
            if (!resolved.isEmpty()) {
                instruments.upsertAll(resolved);
            }
            return resolved;
        });
    }

    // ------------------------------------------------------------------ startup sequence
    @Bean
    public ApplicationRunner startupRunner(StateRecovery recovery, StrategyRegistry registry,
                                           StrategyConfigRepository configRepo,
                                           KillSwitch killSwitch, OrderManager orderManager,
                                           ExecutionProperties props, StreamManager streamManager,
                                           IndicatorSnapshotService snapshotService,
                                           AoDivergenceProperties aoDivProperties) {
        return args -> {
            seedStrategies(configRepo, aoDivProperties);
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
            // Backfill indicator snapshots for any bars missed before the stream started.
            snapshotService.catchUp();
            streamManager.start();
        };
    }

    private void seedStrategies(StrategyConfigRepository repo, AoDivergenceProperties aoDiv) {
        repo.upsert(StrategyConfig.builder().id(SmaCrossStrategy.ID).enabled(true)
                .params(Map.of("fast", "20", "slow", "100")).orderType(OrderType.LIMIT)
                .maxPositionPct(new BigDecimal("0.10")).interval(CandleInterval.ONE_DAY).version(1).build());
        repo.upsert(StrategyConfig.builder().id(EmaMomentumStrategy.ID).enabled(true)
                .params(Map.of("fastEm", "12", "slowEm", "26", "rsiPeriod", "14",
                        "rsiMin", "30", "rsiMax", "70", "atrPeriod", "14", "atrMult", "2"))
                .orderType(OrderType.LIMIT).maxPositionPct(new BigDecimal("0.10"))
                .interval(CandleInterval.FIFTEEN_MIN).version(1).build());
        repo.upsert(StrategyConfig.builder().id(AlligatorAoStrategy.ID).enabled(true)
                .params(Map.of("fast", "5", "slow", "34", "entanglementAtrThreshold", "0.5",
                        "stopLookback", "5", "allowShort", "true", "atrPeriod", "14"))
                .orderType(OrderType.LIMIT).maxPositionPct(new BigDecimal("0.10"))
                .interval(CandleInterval.ONE_HOUR).version(1).build());
        // Registered for backtests but off for live trading until validated by walk-forward.
        repo.upsert(StrategyConfig.builder().id(AoDivergenceReversalStrategy.ID)
                .enabled(aoDiv.isEnabled())
                .params(Map.of("fast", "5", "slow", "34", "stopBufferAtr", "0.3",
                        "thresholdAtrPerBar", "0.05", "allowShort", "true", "atrPeriod", "14"))
                .orderType(OrderType.MARKET).maxPositionPct(new BigDecimal("0.02"))
                .interval(CandleInterval.ONE_HOUR).version(1).build());
    }
}