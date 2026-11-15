package com.traderbro.signalanalysis.domain;

/** Availability of a stored message. Deletion never erases persisted content. */
public enum AvailabilityStatus {
    ACTIVE,
    INACCESSIBLE,
    DELETED
}