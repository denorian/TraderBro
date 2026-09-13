package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sole source of market data for TraderBro. Implemented by the T-Bank Invest API client
 * in the {@code data} package. This interface lives in the domain so that {@code core}
 * and {@code execution} never depend on a broker SDK.
 */
public interface MarketDataProvider {

    /**
     * Loads historical candles for a figi over a time range at a given interval.
     * Implementations must respect the broker's per-call depth limits by chunking.
     *
     * @return closed bars ordered by timestamp ascending
     */
    List<Bar> getHistory(String figi, Instant from, Instant to, CandleInterval interval);

    /**
     * Subscribes to a live candle stream for the given instruments.
     *
     * @param onBar consumer invoked for every candle tick (forming and closed)
     * @return a subscription handle that can be cancelled
     */
    StreamSubscription subscribeCandles(List<String> figis, CandleInterval interval, Consumer<Bar> onBar);

    /**
     * Resolves instruments by ticker via the broker's reference service.
     * Result is cached in the {@code instruments} table and refreshed on a daily schedule.
     */
    List<Instrument> findInstruments(List<String> tickers);
}