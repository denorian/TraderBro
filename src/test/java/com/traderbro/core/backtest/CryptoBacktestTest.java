package com.traderbro.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.CryptoSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.strategies.EmaMomentumStrategy;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.testutil.BarTestData;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

/**
 * Crypto instruments flow through the same long-only backtester as shares: a spot pair
 * (lot = 1) is simulated with the Binance taker fee, producing trades on a trending series.
 */
class CryptoBacktestTest {

    private final BacktestRunner runner = new BacktestRunner(4);
    private final EmaMomentumStrategy strategy = new EmaMomentumStrategy(4);

    private Instrument btc() {
        CryptoSpec spec = CryptoSpec.builder()
                .figi("BTCUSDT").ticker("BTCUSDT")
                .baseAsset("BTC").quoteAsset("USDT")
                .tickSize(new BigDecimal("0.01"))
                .stepSize(new BigDecimal("0.001"))
                .minNotional(new BigDecimal("5"))
                .build();
        return Instrument.builder()
                .figi("BTCUSDT").ticker("BTCUSDT")
                .name("BTC/USDT").currency("USDT").lot(1)
                .minPriceIncrement(new BigDecimal("0.01"))
                .board("BINANCE").tradable(true)
                .instrumentType(InstrumentType.CRYPTO)
                .cryptoSpec(spec)
                .build();
    }

    private StrategyParams params() {
        Map<String, String> m = new HashMap<>();
        m.put("fastEm", "12");
        m.put("slowEm", "26");
        m.put("rsiPeriod", "14");
        m.put("rsiMin", "30");
        m.put("rsiMax", "70");
        m.put("atrPeriod", "14");
        m.put("atrMult", "2");
        return new StrategyParams(m);
    }

    @Test
    void emaMomentumProducesTradesOnCrypto() {
        // Trending series with oscillation that triggers EMA crossovers.
        BarSeries series = BarTestData.seriesFrom(i -> 100 + 10 * Math.sin(2 * Math.PI * i / 40.0)
                + i * 0.25, 300, "BTCUSDT");
        BacktestRunner.BacktestResult result = runner.runDetailed(strategy, series, params(), btc(),
                new BigDecimal("0.001"), new BigDecimal("5"),
                new BigDecimal("100000"), new BigDecimal("0.95"));
        assertThat(result.trades()).isNotEmpty();
        assertThat(result.metrics().numTrades()).isGreaterThan(0);
        // Crypto is not a futures contract: it simulates via the shares path, lot = 1.
        assertThat(btc().isCrypto()).isTrue();
        assertThat(btc().isFuture()).isFalse();
    }
}