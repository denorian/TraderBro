package com.traderbro.api.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable, dependency-free holder of live data-stream health. Shared by {@code StreamManager}
 * (writes) and {@code OrderManager} (reads, via the data-freshness risk rule). Keeping this a
 * leaf bean avoids a bean-construction cycle between the stream and the order manager.
 */
public class StreamHealth {

    private volatile Instant lastReceiveAt = Instant.now();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public void markConnected() {
        connected.set(true);
        lastReceiveAt = Instant.now();
    }

    public void markData() {
        lastReceiveAt = Instant.now();
    }

    public void markDisconnected() {
        connected.set(false);
    }

    public void markDegraded() {
        degraded.set(true);
    }

    public Duration lag() {
        return Duration.between(lastReceiveAt, Instant.now());
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isDegraded() {
        return degraded.get();
    }
}