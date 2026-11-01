package com.traderbro.core.domain.spi;

/**
 * Handle to a live market-data subscription. Returning it decouples callers from the
 * underlying stream implementation (gRPC bi-directional stream in stage 1).
 */
public interface StreamSubscription {

    /** Cancels the subscription and releases its resources. Idempotent. */
    void cancel();

    /** True if the underlying stream is currently connected (data flowing). */
    boolean isConnected();

    /** Cancelled/closed? */
    boolean isCancelled();

    /** True if the stream has permanently degraded after exhausting its reconnect attempts.
     * Defaults to {@code false}; providers that support reconnects override it. */
    default boolean isDegraded() {
        return false;
    }
}