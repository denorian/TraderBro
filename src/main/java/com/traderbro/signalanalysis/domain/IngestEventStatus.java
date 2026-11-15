package com.traderbro.signalanalysis.domain;

/** Lifecycle status of a durable inbox event. */
public enum IngestEventStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    RETRY,
    FAILED,
    IGNORED
}