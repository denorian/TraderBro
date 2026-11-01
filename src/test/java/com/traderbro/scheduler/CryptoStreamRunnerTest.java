package com.traderbro.scheduler;

import com.traderbro.api.config.CryptoProperties;
import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import com.traderbro.core.indicators.IndicatorSnapshotService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards B1 (review 2026-11-01): the {@code bars} upsert is {@code ON CONFLICT DO NOTHING}, so the
 * crypto stream must persist only closed candles. Persisting an in-progress tick would permanently
 * shadow the real final bar (the DB would keep the first tick's OHLCV with {@code is_final=false}).
 */
class CryptoStreamRunnerTest {

    @Test
    void persistsOnlyClosedBars() {
        MarketDataProvider marketData = mock(MarketDataProvider.class);
        BarStore barStore = mock(BarStore.class);
        IndicatorSnapshotService snapshots = mock(IndicatorSnapshotService.class);
        SignalEngine signalEngine = mock(SignalEngine.class);
        when(marketData.subscribeCandles(any(), any(), any())).thenReturn(mock(StreamSubscription.class));

        CryptoProperties props = new CryptoProperties();
        props.setPairs(List.of("BTCUSDT"));
        props.setTimeframes(List.of("ONE_HOUR"));

        CryptoStreamRunner runner = new CryptoStreamRunner(marketData, barStore, snapshots,
                signalEngine, props, new StreamHealth());
        runner.run(mock(ApplicationArguments.class));

        ArgumentCaptor<Consumer<Bar>> cb = ArgumentCaptor.forClass(Consumer.class);
        verify(marketData).subscribeCandles(eq(props.getPairs()), eq(CandleInterval.ONE_HOUR), cb.capture());
        Consumer<Bar> onBar = cb.getValue();

        onBar.accept(bar("BTCUSDT", false));
        verify(barStore, never()).upsert(any());
        verify(snapshots, never()).onClosedBar(any());
        verify(signalEngine, never()).onClosedBar(any());

        onBar.accept(bar("BTCUSDT", true));
        verify(barStore, times(1)).upsert(any());
        verify(snapshots, times(1)).onClosedBar(any());
        verify(signalEngine, times(1)).onClosedBar(any());
    }

    private static Bar bar(String figi, boolean final_) {
        return Bar.builder()
                .figi(figi)
                .interval(CandleInterval.ONE_HOUR)
                .ts(Instant.parse("2026-10-01T00:00:00Z"))
                .open(BigDecimal.valueOf(60000))
                .high(BigDecimal.valueOf(61000))
                .low(BigDecimal.valueOf(59000))
                .close(BigDecimal.valueOf(60500))
                .volume(100L)
                .final_(final_)
                .build();
    }
}