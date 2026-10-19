package com.traderbro.api.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tracked share tickers loaded into the instrument catalog ({@code instruments.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "instruments")
public class InstrumentsProperties {

    /** Share tickers to resolve from the broker and include in streams/signals/backtests. */
    private List<String> tickers = List.of("SBER", "GAZP", "LKOH", "YDEX", "VTBR");
}