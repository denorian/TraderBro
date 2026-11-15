package com.traderbro.signalanalysis.domain;

/** Kind of a sync run recorded in {@code telegram_sync_runs}. */
public enum SyncRunType {
    CHANNEL_DISCOVERY,
    FULL_BACKFILL,
    TAIL_RECONCILE
}