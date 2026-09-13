package com.traderbro.data.tbank;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.MarketDataProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;

/**
 * Backfills historical bars into the {@code bars} hypertable on startup when
 * {@code app.history.load-on-startup=true}. Insertions are idempotent via {@link BarStore}.
 */
@Slf4j
public class HistoryLoader implements CommandLineRunner {

    private final MarketDataProvider marketData;
    private final BarStore barStore;
    private final HistoryLoadConfig config;
    private final InstrumentResolver instruments;

    public HistoryLoader(MarketDataProvider marketData, BarStore barStore,
                         HistoryLoadConfig config, InstrumentResolver instruments) {
        this.marketData = marketData;
        this.barStore = barStore;
        this.config = config;
        this.instruments = instruments;
    }

    @Override
    public void run(String... args) {
        if (!config.isLoadOnStartup()) {
            log.info("History backfill disabled (app.history.load-on-startup=false)");
            return;
        }
        Instant now = Instant.now();
        List<Instrument> resolved = instruments.resolve(config.getTickers());
        for (Instrument inst : resolved) {
            loadDaily(inst, now);
            loadIntraday(inst, now);
            for (CandleInterval extra : config.getExtraIntervals()) {
                loadRange(inst, now.minus(config.getIntradayDays(), ChronoUnit.DAYS), now, extra);
            }
        }
    }

    private void loadDaily(Instrument inst, Instant now) {
        Instant from = now.minus(config.getDailyYears(), ChronoUnit.YEARS);
        loadRange(inst, from, now, CandleInterval.ONE_DAY);
    }

    private void loadIntraday(Instrument inst, Instant now) {
        Instant from = now.minus(config.getIntradayDays(), ChronoUnit.DAYS);
        loadRange(inst, from, now, config.getIntradayInterval());
    }

    private void loadRange(Instrument inst, Instant from, Instant to, CandleInterval interval) {
        try {
            List<Bar> bars = marketData.getHistory(inst.getFigi(), from, to, interval);
            barStore.upsert(bars);
            log.info("Backfilled {} bars for {} interval {} ({}..{})",
                    bars.size(), inst.getTicker(), interval, from, to);
        } catch (RuntimeException e) {
            log.error("Backfill failed for {} interval {}: {}", inst.getTicker(), interval, e.getMessage());
        }
    }

    /** Resolves tickers to instruments (used to decouple loader from reference caching). */
    public interface InstrumentResolver {
        List<Instrument> resolve(List<String> tickers);
    }
}