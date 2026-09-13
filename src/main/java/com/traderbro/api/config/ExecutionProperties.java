package com.traderbro.api.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Execution properties ({@code execution.*}). */
@Getter
@Setter
@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

    /** Order lifetime before automatic cancellation. */
    private Duration orderTtl = Duration.ofSeconds(60);
    /** Whether reconciliation discrepancy auto-activates the kill-switch. */
    private boolean autoKillOnReconcileDiscrepancy = true;
    /** Whether the kill-switch also liquidates open positions. */
    private boolean liquidateOnKill = false;
}