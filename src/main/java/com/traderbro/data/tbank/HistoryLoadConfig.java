package com.traderbro.data.tbank;

import com.traderbro.core.domain.enums.CandleInterval;
import java.util.List;
import lombok.Value;

/**
 * Configuration for the initial history backfill on startup.
 */
@Value
public class HistoryLoadConfig {

    boolean loadOnStartup;
    /** Tickers to backfill. */
    List<String> tickers;
    /** Years of daily candles to load. */
    int dailyYears;
    /** Days of intraday candles to load (e.g. 15-min). */
    int intradayDays;
    CandleInterval intradayInterval;
    List<CandleInterval> extraIntervals;
}