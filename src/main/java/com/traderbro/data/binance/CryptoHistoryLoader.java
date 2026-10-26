package com.traderbro.data.binance;

import com.traderbro.api.config.CryptoProperties;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.storage.InstrumentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;

/**
 * Startup backfill for crypto bars. Always resolves and persists the tracked spot pairs into the
 * {@code instruments} table (so they show up in the UI and are backtestable), then, when
 * {@code crypto.history.load-on-startup=true}, loads 3 years of daily + {@code days-intraday} of
 * 15-minute candles. Inserts are idempotent via {@link BarStore} and incremental via
 * {@link BarStore#latestTs}.
 */
@Slf4j
public class CryptoHistoryLoader implements CommandLineRunner {

    private final MarketDataProvider marketData;
    private final BarStore barStore;
    private final InstrumentRepository instruments;
    private final CryptoProperties props;

    public CryptoHistoryLoader(MarketDataProvider marketData, BarStore barStore,
                               InstrumentRepository instruments, CryptoProperties props) {
        this.marketData = marketData;
        this.barStore = barStore;
        this.instruments = instruments;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!props.isEnabled()) {
            log.info("crypto disabled (crypto.enabled=false)");
            return;
        }
        List<Instrument> resolved = marketData.findInstruments(props.getPairs());
        if (resolved.isEmpty()) {
            log.warn("crypto instruments not resolved; skipping crypto setup");
            return;
        }
        instruments.upsertAll(resolved);
        log.info("crypto instruments persisted: {}", resolved.size());

        if (!props.getHistory().isLoadOnStartup()) {
            log.info("crypto history backfill disabled (crypto.history.load-on-startup=false)");
            return;
        }
        Instant now = Instant.now();
        // 3 years of daily candles.
        Instant dailyFrom = now.atZone(java.time.ZoneOffset.UTC).minusYears(3).toInstant();
        // 400 days of 1-hour candles.
        Instant hourlyFrom = now.minus(props.getHistory().getDaysHourly(), ChronoUnit.DAYS);
        // 90 days of 15-minute candles.
        Instant intradayFrom = now.minus(props.getHistory().getDaysIntraday(), ChronoUnit.DAYS);
        for (Instrument inst : resolved) {
            loadRange(inst, dailyFrom, now, CandleInterval.ONE_DAY);
            loadRange(inst, hourlyFrom, now, CandleInterval.ONE_HOUR);
            loadRange(inst, hourlyFrom, now, CandleInterval.FOUR_HOUR);
            loadRange(inst, intradayFrom, now, CandleInterval.FIFTEEN_MIN);
        }
    }

    private void loadRange(Instrument inst, Instant from, Instant to, CandleInterval interval) {
        try {
            Instant start = from;
            java.util.Optional<Instant> earliest = barStore.earliestTs(inst.getFigi(), interval);
            java.util.Optional<Instant> latest = barStore.latestTs(inst.getFigi(), interval);
            // Only skip the historical window when it is already filled (the earliest stored bar is
            // at or before the window start). A bare candle written by the live stream would make
            // latestTs() look current and wrongly skip the whole history, so we key the decision
            // on the EARLIEST bar, not the latest. BarStore.upsert is idempotent, so re-fetching a
            // few overlapping bars is safe.
            if (earliest.isPresent() && !earliest.get().isAfter(from) && latest.isPresent()) {
                start = latest.get().isAfter(from) ? latest.get() : from;
            }
            if (start.compareTo(to) >= 0) {
                log.debug("crypto {} {} up to date", inst.getTicker(), interval);
                return;
            }
            List<Bar> bars = marketData.getHistory(inst.getFigi(), start, to, interval);
            barStore.upsert(bars);
            log.info("crypto backfilled {} bars for {} {} ({}..{})",
                    bars.size(), inst.getTicker(), interval, start, to);
        } catch (RuntimeException e) {
            log.error("crypto backfill failed for {} {}: {}",
                    inst.getTicker(), interval, e.getMessage());
        }
    }
}