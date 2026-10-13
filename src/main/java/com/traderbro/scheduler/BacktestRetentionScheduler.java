package com.traderbro.scheduler;

import com.traderbro.api.config.BacktestProperties;
import com.traderbro.storage.BacktestBatchRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Purges finished backtest results older than {@code backtest.results-retention-days}.
 * Ran daily; harmless when there is nothing to purge.
 */
@Slf4j
public class BacktestRetentionScheduler {

    private final BacktestBatchRepository repository;
    private final int retentionDays;

    public BacktestRetentionScheduler(BacktestBatchRepository repository,
                                      BacktestProperties props) {
        this.repository = repository;
        this.retentionDays = props.getResultsRetentionDays();
    }

    @Scheduled(cron = "${backtest.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.purgeOlderThan(cutoff);
        if (deleted > 0) {
            log.info("purged {} expired backtest runs (retention {}d)", deleted, retentionDays);
        }
    }
}