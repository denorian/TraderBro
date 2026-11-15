package com.traderbro.signalanalysis.domain;

/** Why a message version was recorded (append-only history). */
public enum ChangeType {
    /** Live message observed for the first time. */
    LIVE_NEW,
    /** Message observed during full history backfill (only current snapshot is available). */
    BACKFILL,
    /** Content edit observed live. */
    EDIT,
    /** Backfill found a newer snapshot than what was previously stored. */
    SYNC_CORRECTION
}