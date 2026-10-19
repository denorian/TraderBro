package com.traderbro.api.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parameters for the AO-divergence reversal strategy ({@code strategies.ao-divergence.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "strategies.ao-divergence")
public class AoDivergenceProperties {

    /** Off until validated by a walk-forward backtest. */
    private boolean enabled = false;
    private List<String> timeframes = List.of("FIFTEEN_MIN", "ONE_HOUR");
    /** Stop distance beyond the price extremum at the second AO peak, in ATR. */
    private double stopBufferAtr = 0.3;
    /** Fraction of equity risked per position (percent). */
    private double riskCapitalPct = 1.0;
    /** Shorts allowed (futures only). */
    private boolean allowShort = true;
    /** ATR period for stop sizing. */
    private int atrPeriod = 14;
}