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
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.MarketDataResponse;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.contract.v1.SubscriptionInterval;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.MarketDataService;
import ru.tinkoff.piapi.core.stream.MarketDataStreamService;
import ru.tinkoff.piapi.core.stream.MarketDataSubscriptionService;
import ru.tinkoff.piapi.core.stream.StreamProcessor;

/**
 * {@link MarketDataProvider} backed by the T-Bank Invest API (java-sdk-core 1.36).
 *
 * <p>Retries apply only to read operations. Stream reconnection uses exponential backoff
 * (max {@link #MAX_RECONNECT_ATTEMPTS}); after exhausting attempts the subscription is marked
 * degraded.
 */
@Slf4j
public class TBankMarketDataProvider implements MarketDataProvider {

    static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final MarketDataService marketDataService;
    private final InstrumentsService instrumentsService;
    private final MarketDataStreamService streamService;
    private final SdkBarMapper barMapper;
    private final SdkInstrumentMapper instrumentMapper;
    private final int historyMaxCandles;
    private final RetryPolicy retryPolicy;
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final List<StreamSubscriptionImpl> subscriptions = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger streamIdCounter = new java.util.concurrent.atomic.AtomicInteger();

    public TBankMarketDataProvider(InvestApi api, SdkBarMapper barMapper,
                                   SdkInstrumentMapper instrumentMapper,
                                   int historyMaxCandles, RetryPolicy retryPolicy) {
        this.marketDataService = api.getMarketDataService();
        this.instrumentsService = api.getInstrumentsService();
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
                    marketDataService.getCandlesSync(figi, chunk.start(), chunk.end(), toSdk(interval)),
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
            StreamProcessor<MarketDataResponse> processor = response -> {
                sub.connected.set(true);
                degraded.set(false);
                if (response.hasCandle()) {
                    sub.onBar.accept(barMapper.toBar(response.getCandle(), sub.interval));
                }
            };
            String streamId = "candles-" + streamIdCounter.incrementAndGet();
            MarketDataSubscriptionService subscription =
                    streamService.newStream(streamId, processor, err -> {
                        log.warn("candle stream error: {}", err.getMessage());
                        sub.connected.set(false);
                    });
            subscription.subscribeCandles(sub.figis, toSubscriptionInterval(sub.interval));
            sub.setSubscription(subscription);
            sub.connected.set(true);
            log.info("Subscribed candles for {} at {} (attempt {})", sub.figis, sub.interval, attempt + 1);
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
        List<Share> shares = withRetry(instrumentsService::getTradableSharesSync, "findInstruments.shares");
        for (Share s : shares) {
            if (tickers.contains(s.getTicker())) {
                result.add(instrumentMapper.toInstrument(s));
            }
        }
        return result;
    }

    /**
     * Loads futures from the broker reference service and selects the nearest eligible
     * contract per requested basic asset.
     */
    public List<Instrument> findFutures(List<String> basicAssets, int minDaysToExpiry) {
        List<Instrument> all = futuresFor(basicAssets);
        return com.traderbro.core.futures.FuturesContractSelector
                .selectNearest(all, basicAssets, java.time.LocalDate.now(), minDaysToExpiry);
    }

    /** All futures for the requested basic assets (no nearest-contract selection). */
    public List<Instrument> findAllFutures(List<String> basicAssets) {
        return futuresFor(basicAssets);
    }

    private List<Instrument> futuresFor(List<String> basicAssets) {
        List<Instrument> all = new ArrayList<>();
        List<Future> futures = withRetry(instrumentsService::getTradableFuturesSync, "findFutures.list");
        for (Future f : futures) {
            if (f.getBlockedTcaFlag()) {
                continue;
            }
            Instrument inst = instrumentMapper.toInstrument(f);
            if (inst.getFutureSpec() != null && basicAssets.contains(inst.getFutureSpec().getBasicAsset())) {
                all.add(inst);
            }
        }
        return all;
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

    /** Maps domain interval to the historical-request interval enum. */
    static ru.tinkoff.piapi.contract.v1.CandleInterval toSdk(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_1_MIN;
            case FIVE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_5_MIN;
            case FIFTEEN_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_15_MIN;
            case ONE_HOUR -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_HOUR;
            case ONE_DAY -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_DAY;
        };
    }

    /** Maps domain interval to the stream-subscription interval enum. */
    static SubscriptionInterval toSubscriptionInterval(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_MINUTE;
            case FIVE_MIN -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_FIVE_MINUTES;
            case FIFTEEN_MIN -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_FIFTEEN_MINUTES;
            case ONE_HOUR -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_HOUR;
            case ONE_DAY -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_DAY;
        };
    }

    /** Internal subscription handle wrapping a {@link MarketDataSubscriptionService}. */
    private static final class StreamSubscriptionImpl implements StreamSubscription {
        final List<String> figis;
        final CandleInterval interval;
        final Consumer<Bar> onBar;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean connected = new AtomicBoolean(false);
        volatile MarketDataSubscriptionService subscription;

        StreamSubscriptionImpl(List<String> figis, CandleInterval interval, Consumer<Bar> onBar) {
            this.figis = figis;
            this.interval = interval;
            this.onBar = onBar;
        }

        void setSubscription(MarketDataSubscriptionService subscription) {
            this.subscription = subscription;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                connected.set(false);
                if (subscription != null) {
                    subscription.cancel();
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