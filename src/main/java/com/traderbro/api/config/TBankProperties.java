package com.traderbro.api.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** T-Bank Invest API connection properties ({@code tbank.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "tbank")
public class TBankProperties {

    /** Environment variable holding the API token (never read from config/yml). */
    private String tokenEnv = "TBANK_TOKEN";
    /** Target endpoint override (production vs sandbox domain). */
    private String target = "invest-public-api.tinkoff.ru";
    private Duration timeout = Duration.ofSeconds(10);
    /** Max unary (read) API requests per minute; keeps HistoryLoader under the broker's
     * per-minute limit (error 80002) so backfills complete without dropped instruments. */
    private int rateLimitPerMin = 20;
    private Retry retry = new Retry();

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(500);
    }
}