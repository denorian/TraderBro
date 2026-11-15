package com.traderbro.signalanalysis.domain;

/** Lifecycle status of a sync run. */
public enum SyncRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}