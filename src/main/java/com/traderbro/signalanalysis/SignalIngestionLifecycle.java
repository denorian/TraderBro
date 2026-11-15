package com.traderbro.signalanalysis;

import java.time.Instant;

/**
 * Facade over the conditional {@code SignalIngestionLifecycleManager}. When ingestion is disabled
 * no implementation bean exists and the REST layer reports {@code enabled=false}. Keeps the API
 * package free of a hard dependency on the native client.
 */
public interface SignalIngestionLifecycle {

    boolean isActive();

    Instant lastHeartbeat();

    Instant lastDiscoveryAt();

    /** Trigger a manual channel-list discovery; returns a sync run id, or null when unavailable. */
    String triggerDiscovery();

    void close();
}