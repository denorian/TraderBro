package com.traderbro.signalanalysis.domain;

/**
 * Effective ingestion status of a single channel, derived from monitoring flag, active
 * subscription, Telegram authorization state and history state. Computed on read; not persisted.
 */
public enum SignalIngestionStatus {
    /** Monitoring disabled (no collection). */
    DISABLED,
    /** Monitoring enabled but Telegram session requires authorization. */
    WAITING_AUTH,
    /** Monitoring enabled, awaiting scheduling. */
    PENDING,
    /** Full history backfill in progress. */
    BACKFILL,
    /** Monitoring active and synchronized. */
    ACTIVE,
    /** Monitoring active but degraded (partial history, transient errors). */
    DEGRADED,
    /** Subscription lost or channel inaccessible. */
    UNAVAILABLE,
    /** Permanent/fatal error for this channel. */
    ERROR
}