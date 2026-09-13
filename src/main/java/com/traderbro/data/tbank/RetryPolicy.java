package com.traderbro.data.tbank;

import java.time.Duration;
import lombok.Value;

/**
 * Retry/backoff policy for broker read operations. Read operations may retry; order
 * placement must never auto-retry (handled by execution, not here).
 */
@Value
public class RetryPolicy {

    int maxAttempts;
    Duration initialBackoff;
    Duration timeout;

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(10));
    }
}