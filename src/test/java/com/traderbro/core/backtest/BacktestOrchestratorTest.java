package com.traderbro.core.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.traderbro.api.config.BacktestProperties;
import com.traderbro.api.config.CryptoProperties;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.strategies.SmaCrossStrategy;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.strategies.TradingStrategy;
import com.traderbro.storage.BacktestBatchRepository;
import com.traderbro.storage.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

/** Orchestrator behaviour: validation, cancel, and no leakage into the trading circuit. */
class BacktestOrchestratorTest {

    private final BacktestRunner runner = new BacktestRunner(4);
    private final WalkForwardRunner wf = new WalkForwardRunner(runner);
    private final BarStore barStore = mock(BarStore.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final BacktestBatchRepository repository = mock(BacktestBatchRepository.class);
    private final StrategyRegistry registry = mock(StrategyRegistry.class);
    private final StrategyConfigProvider configProvider = mock(StrategyConfigProvider.class);
    private final TraderEventPublisher publisher = mock(TraderEventPublisher.class);
    private final BacktestProperties props = new BacktestProperties();

    private BacktestOrchestrator orchestrator() {
        return new BacktestOrchestrator(runner, wf, barStore, instruments, repository, registry,
                configProvider, publisher, "test", props, new CryptoProperties());
    }

    private BacktestRequest validRequest() {
        return BacktestRequest.builder()
                .strategyIds(List.of("SmaCrossStrategy"))
                .figis(List.of("BBG004730RP0"))
                .interval(CandleInterval.ONE_HOUR)
                .from(Instant.parse("2026-01-01T00:00:00Z"))
                .to(Instant.parse("2026-10-01T00:00:00Z"))
                .costs(BacktestCosts.builder().commissionPct(new BigDecimal("0.0005")).slippageBps(5).build())
                .walkForward(false)
                .build();
    }

    @Test
    void submitRejectsUnknownStrategy() {
        when(registry.factory("Nope")).thenReturn(null);
        BacktestOrchestrator o = orchestrator();
        BacktestRequest req = BacktestRequest.builder()
                .strategyIds(List.of("Nope"))
                .figis(List.of("BBG004730RP0"))
                .interval(CandleInterval.ONE_HOUR)
                .from(Instant.parse("2026-01-01T00:00:00Z"))
                .to(Instant.parse("2026-10-01T00:00:00Z"))
                .build();
        assertThrows(IllegalArgumentException.class, () -> o.submit(req));
    }

    @Test
    void submitRejectsReversedPeriod() {
        BacktestOrchestrator o = orchestrator();
        BacktestRequest req = BacktestRequest.builder()
                .strategyIds(List.of("SmaCrossStrategy"))
                .figis(List.of("BBG004730RP0"))
                .interval(CandleInterval.ONE_HOUR)
                .from(Instant.parse("2026-10-01T00:00:00Z"))
                .to(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        assertThrows(IllegalArgumentException.class, () -> o.submit(req));
    }

    @Test
    void cancelUnknownRunReturnsFalse() {
        assertFalse(orchestrator().cancel("missing-id"));
    }

    @Test
    void orchestratorNeverTouchesBrokerOrOrderTables() {
        // The orchestrator's dependency set simply has no broker gateway / order store — the
        // only writes go to the backtest repository. Verify submit only uses backtest storage.
        when(registry.factory("SmaCrossStrategy"))
                .thenReturn(new SmaCrossStrategy(4));
        when(instruments.findByFigi("BBG004730RP0"))
                .thenReturn(Optional.of(Instrument.builder().figi("BBG004730RP0")
                        .ticker("SBER").name("Sber").lot(10)
                        .minPriceIncrement(new BigDecimal("0.01")).tradable(true)
                        .instrumentType(InstrumentType.SHARE).build()));
        when(configProvider.findById("SmaCrossStrategy"))
                .thenReturn(Optional.empty());
        when(barStore.findByFigiAndInterval(eq("BBG004730RP0"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(linearBars(200));

        String id = orchestrator().submit(validRequest());
        assertTrue(id != null && !id.isBlank());
        // Only backtest storage is used; no trading-side writes by construction.
        verify(repository).insertRun(any(BacktestRun.class));
    }

    private List<com.traderbro.core.domain.Bar> linearBars(int n) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        java.util.ArrayList<com.traderbro.core.domain.Bar> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double m = 100 + i;
            out.add(com.traderbro.core.domain.Bar.builder().figi("BBG004730RP0")
                    .interval(CandleInterval.ONE_HOUR)
                    .ts(start.plus(Duration.ofHours(i)))
                    .open(BigDecimal.valueOf(m)).high(BigDecimal.valueOf(m + 1))
                    .low(BigDecimal.valueOf(m - 1)).close(BigDecimal.valueOf(m))
                    .volume(100).final_(true).build());
        }
        return out;
    }
}