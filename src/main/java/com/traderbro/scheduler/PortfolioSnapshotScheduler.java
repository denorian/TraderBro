package com.traderbro.scheduler;

import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.storage.PortfolioSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Snapshots portfolio valuation and daily P&L on a schedule. */
@Slf4j
@RequiredArgsConstructor
public class PortfolioSnapshotScheduler {

    private final PortfolioProvider portfolioProvider;
    private final PortfolioSnapshotRepository snapshotRepository;

    @Scheduled(fixedDelayString = "${scheduler.portfolio-snapshot-ms:300000}")
    public void run() {
        try {
            snapshotRepository.insert(portfolioProvider.snapshot());
        } catch (RuntimeException e) {
            log.error("portfolio snapshot failed: {}", e.getMessage());
        }
    }
}