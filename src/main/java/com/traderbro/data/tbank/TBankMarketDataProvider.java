package com.traderbro.data.tbank;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import com.traderbro.data.mapper.SdkBarMapper;
import com.traderbro.data.mapper.SdkInstrumentMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.contract.v1.SubscriptionInterval;
import ru.ttech.piapi.core.connector.streaming.StreamManagerFactory;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.CandleSubscriptionSpec;

/**
 * {@link MarketDataProvider} backed by the T-Bank Invest API (java-sdk-core 1.51).
 *
 * <p>History requests go through {@link TBankApi} (SDK resilience retry); live candles are
 * streamed via the SDK {@link MarketDataStreamManager}, which handles reconnection itself.
 */
@Slf4j
public class TBankMarketDataProvider implements MarketDataProvider {

    private final TBankApi api;
    private final SdkBarMapper barMapper;
    private final SdkInstrumentMapper instrumentMapper;
    private final int historyMaxCandles;
    private final ApiRateLimiter rateLimiter;
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final ExecutorService streamExecutor;
    private volatile MarketDataStreamManager streamManager;
    private final List<StreamSubscriptionImpl> subscriptions = new ArrayList<>();

    public TBankMarketDataProvider(TBankApi api, SdkBarMapper barMapper,
                                   SdkInstrumentMapper instrumentMapper,
                                   int historyMaxCandles, ApiRateLimiter rateLimiter) {
        this.api = api;
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
        this.historyMaxCandles = historyMaxCandles;
        this.rateLimiter = rateLimiter;
        this.streamExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public List<Bar> getHistory(String figi, Instant from, Instant to, CandleInterval interval) {
        List<Bar> result = new ArrayList<>();
        for (HistoryChunker.Chunk chunk : HistoryChunker.chunk(from, to, interval, historyMaxCandles)) {
            rateLimiter.acquire();
            List<HistoricCandle> candles = api.getCandlesSync(figi, chunk.start(), chunk.end(), toSdk(interval));
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
        MarketDataStreamManager manager = streamManager();
        SubscriptionInterval sdkInterval = toSubscriptionInterval(interval);
        Set<ru.ttech.piapi.core.impl.marketdata.subscription.Instrument> instruments = new HashSet<>();
        for (String figi : figis) {
            instruments.add(new ru.ttech.piapi.core.impl.marketdata.subscription.Instrument(figi, sdkInterval));
        }
        manager.subscribeCandles(instruments, new CandleSubscriptionSpec(), candle ->
                sub.onBar.accept(barMapper.toBar(candle.getOriginal(), sub.interval)));
        sub.markConnected();
        log.info("Subscribed candles for {} at {}", figis, interval);
        return sub;
    }

    private MarketDataStreamManager streamManager() {
        MarketDataStreamManager current = streamManager;
        if (current == null) {
            synchronized (this) {
                current = streamManager;
                if (current == null) {
                    current = StreamManagerFactory.create(api.streamFactory())
                            .newMarketDataStreamManager(streamExecutor, api.scheduledExecutor());
                    current.start();
                    streamManager = current;
                }
            }
        }
        return current;
    }

    @Override
    public List<Instrument> findInstruments(List<String> tickers) {
        List<Instrument> result = new ArrayList<>();
        for (Share s : api.getSharesSync()) {
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
        for (Future f : api.getFuturesSync()) {
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
        subscriptions.clear();
        MarketDataStreamManager manager = streamManager;
        if (manager != null) {
            manager.shutdown();
        }
        streamExecutor.shutdownNow();
    }

    /** Maps domain interval to the historical-request interval enum. */
    static ru.tinkoff.piapi.contract.v1.CandleInterval toSdk(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_1_MIN;
            case FIVE_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_5_MIN;
            case FIFTEEN_MIN -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_15_MIN;
            case ONE_HOUR -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_HOUR;
            case FOUR_HOUR -> ru.tinkoff.piapi.contract.v1.CandleInterval.CANDLE_INTERVAL_4_HOUR;
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
            case FOUR_HOUR -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_4_HOUR;
            case ONE_DAY -> SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_DAY;
        };
    }

    /** Internal subscription handle backed by the {@link MarketDataStreamManager}. */
    private static final class StreamSubscriptionImpl implements StreamSubscription {
        final List<String> figis;
        final CandleInterval interval;
        final Consumer<Bar> onBar;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean connected = new AtomicBoolean(false);

        StreamSubscriptionImpl(List<String> figis, CandleInterval interval, Consumer<Bar> onBar) {
            this.figis = figis;
            this.interval = interval;
            this.onBar = onBar;
        }

        void markConnected() {
            connected.set(true);
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                connected.set(false);
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