package com.traderbro.api.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Futures contract selection and rollover configuration ({@code futures.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "futures")
public class FuturesProperties {

    private boolean enabled = true;
    /** Underlying assets for nearest-contract auto-selection. */
    private List<String> basicAssets = List.of("IMOEX", "Si", "BR");
    /** Skip contracts expiring within this many days when selecting. */
    private int minDaysToExpiry = 7;
    /** Warn about rollover when this many days before expiry. */
    private int rolloverDaysBefore = 5;
    /** Lock new positions within this many days before expiry. */
    private int noNewPositionsDaysBeforeExpiry = 2;
}