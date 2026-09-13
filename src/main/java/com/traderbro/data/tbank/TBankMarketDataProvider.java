package com.traderbro.data.tbank;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import com.traderbro.data.mapper.SdkBarMapper;
import com.traderbro.data.mapper.SdkInstrumentMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.MarketDataService;
import ru.tinkoff.piapi.core.MarketDataStreamService;

/**
 * {@link MarketDataProvider} backed by the T-Bank Invest API.
 *
 * <p>Retries apply only to read operations. Stream reconnection uses exponential backoff
 * (max {@link #MAX_RECONNECT_ATTEMPTS}); after exhausting attempts the subscription is marked
 * degraded and surfaced via {@code ComponentStatus}.
 *
 * <p>NOTE on SDK API: method names below follow the T-Bank Java SDK and must be verified
 * against the pinned version. Each uncertain call is marked {@code // TODO: verify API}.
 */
@Slf4j
public class TBankMarketDataProvider implements MarketDataProvider {

    static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final InvestApi api;
    private final MarketDataService marketDataService;
    private final MarketDataStreamService streamService;
    private final SdkBarMapper barMapper;
    private final SdkInstrumentMapper instrumentMapper;
    private final int historyMaxCandles;
    private final RetryPolicy retryPolicy;
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final List<StreamSubscriptionImpl> subscriptions = new ArrayList<>();

    public TBankMarketDataProvider(InvestApi api, SdkBarMapper barMapper,
                                   SdkInstrumentMapper instrumentMapper,
                                   int historyMaxCandles, RetryPolicy retryPolicy) {
        this.api = api;
        this.marketDataService = api.getMarketDataService();
        this.streamService = api.getMarketDataStreamService();
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
        this.historyMaxCandles = historyMaxCandles;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public List<Bar> getHistory(String figi, Instant from, Instant to, CandleInterval interval) {
        List<Bar> result = new ArrayList<>();
        for (HistoryChunker.Chunk chunk : HistoryChunker.chunk(from, to, interval, historyMaxCandles)) {
            List<HistoricCandle> candles = withRetry(() ->
                    // TODO: verify API — getCandles(figi, from, to, interval) signature.
                    marketDataService.getCandles(figi, chunk.start(), chunk.end(), toSdk(interval)),
                    "getHistory." + figi);
            for (HistoricCandle c : candles) {
                if (c.getIsComplete()) {
                    result.add(barMapper.toBar(figi, c, interval));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized StreamSubscription subscribeCandles(List<String> figis, CandleInterval interval,
                                                            Consumer<Bar> onBar) {
        StreamSubscriptionImpl sub = new StreamSubscriptionImpl(figis, interval, onBar);
        subscriptions.add(sub);
        connectWithBackoff(sub, 0);
        return sub;
    }

    private void connectWithBackoff(StreamSubscriptionImpl sub, int attempt) {
        if (sub.isCancelled()) {
            return;
        }
        try {
            // TODO: verify API — sandbox market data uses a dedicated stream service.
            // In sandbox mode use api.getSandboxMarketDataStreamService() instead.
            ru.tinkoff.piapi.core.StreamService<ru.tinkoff.piapi.contract.v1.StreamCandle> candleStream =
                    streamService.getCandlesStream();
            candleStream.subscribe(c -> {
                sub.connected.set(true);
                onBar.accept(barMapper.toBar(c.getCandle(), interval));
            });
            // TODO: verify API — subscription initiation call name and parameters.
            streamService.subscribeCandles(sub.figis, toSdk(interval));
            sub.setStream(candleStream);
            degraded.set(false);
            sub.connected.set(true);
            log.info("Subscribed candles for {} (attempt {})", sub.figis, attempt + 1);
        } catch (RuntimeException e) {
            sub.connected.set(false);
            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                long delayMillis = retryPolicy.getInitialBackoff().toMillis() * (1L << attempt);
                log.warn("Candle stream connect failed (attempt {}); retrying in {}ms: {}",
                        attempt + 1, delayMillis, e.getMessage());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                connectWithBackoff(sub, attempt + 1);
            } else {
                degraded.set(true);
                log.error("Candle stream exhausted {} reconnect attempts; marking degraded", MAX_RECONNECT_ATTEMPTS);
            }
        }
    }

    @Override
    public List<Instrument> findInstruments(List<String> tickers) {
        List<Instrument> result = new ArrayList<>();
        List<Share> shares = withRetry(() ->
                        // TODO: verify API — getShares() returns List<Share>.
                        api.getInstrumentsService().getShares(),
                "findInstruments.shares");
        for (Share s : shares) {
            if (tickers.contains(s.getTicker())) {
                result.add(instrumentMapper.toInstrument(s));
            }
        }
        return result;
    }

    /** True if the live stream has been marked degraded after failed reconnects. */
    public boolean isDegraded() {
        return degraded.get();
    }

    /** Cancels all active subscriptions (used at shutdown). */
    public synchronized void cancelAll() {
        for (StreamSubscriptionImpl s : subscriptions) {
            s.cancel();
        }
    }

    private <T> T withRetry(java.util.function.Supplier<T> action, String op) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                attempt++;
                if (attempt >= retryPolicy.getMaxAttempts()) {
                    throw e;
                }
                long delay = retryPolicy.getInitialBackoff().toMillis() * (1L << (attempt - 1));
                log.warn("{} failed (attempt {}/{}), backing off {}ms: {}",
                        op, attempt, retryPolicy.getMaxAttempts(), delay, e.getMessage());
                sleep(delay);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", e);
        }
    }

    /** Maps domain interval to SDK interval. */
    // TODO: verify API — SDK CandleInterval enum constant names.
    static ru.tinkoff.piapi.contract.v1.CandleInterval toSdk(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_1_MIN;
            case FIVE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_5_MIN;
            case FIFTEEN_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_15_MIN;
            case ONE_HOUR -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_HOUR;
            case ONE_DAY -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_DAY;
        };
    }

    /** Internal subscription handle. */
    private static final class StreamSubscriptionImpl implements StreamSubscription {
        final List<String> figis;
        final CandleInterval interval;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean connected = new AtomicBoolean(false);
        volatile ru.tinkoff.piapi.core.StreamService<ru.tinkoff.piapi.contract.v1.StreamCandle> stream;

        StreamSubscriptionImpl(List<String> figis, CandleInterval interval, Consumer<Bar> onBar) {
            this.figis = figis;
            this.interval = interval;
        }

        void setStream(ru.tinkoff.piapi.core.StreamService<ru.tinkoff.piapi.contract.v1.StreamCandle> s) {
            this.stream = s;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                connected.set(false);
                if (stream != null) {
                    // TODO: verify API — StreamService.unsubscribe() name.
                    stream.unsubscribe();
                }
            }
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}