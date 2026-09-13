package com.traderbro.scheduler;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.storage.InstrumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Refreshes the local instrument reference cache daily (Europe/Moscow 08:00). */
@Slf4j
@RequiredArgsConstructor
public class InstrumentRefreshScheduler {

    private final MarketDataProvider marketData;
    private final InstrumentRepository repository;
    private final List<String> tickers;

    @Scheduled(cron = "${scheduler.instrument-refresh-cron:0 0 8 * * *}", zone = "Europe/Moscow")
    public void run() {
        try {
            List<Instrument> resolved = marketData.findInstruments(tickers);
            repository.upsertAll(resolved);
            log.info("instrument cache refreshed: {} instruments", resolved.size());
        } catch (RuntimeException e) {
            log.error("instrument refresh failed: {}", e.getMessage());
        }
    }
}