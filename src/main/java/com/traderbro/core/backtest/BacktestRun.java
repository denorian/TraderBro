package com.traderbro.core.backtest;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * An orchestrated batch backtest run. Header-level record stored in {@code backtest_runs}
 * (with {@code strategy_id}/{@code figi} left NULL); per-pair outcomes live in
 * {@code backtest_pair_results} and the detail tables. {@link #results} is populated only when
 * the full detail is loaded.
 */
@Value
@Builder
public class BacktestRun {

    String id;
    String status;
    BacktestRequest request;
    int progressPct;
    String errorMessage;
    String codeVersion;
    Instant createdAt;
    Instant startedAt;
    Instant finishedAt;
    @Builder.Default
    List<PairResult> results = List.of();

    public static BacktestRun queued(String id, BacktestRequest request, String codeVersion) {
        return BacktestRun.builder()
                .id(id)
                .status(BacktestRunStatus.QUEUED)
                .request(request)
                .progressPct(0)
                .codeVersion(codeVersion)
                .createdAt(Instant.now())
                .results(List.of())
                .build();
    }
}