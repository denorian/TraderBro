package com.traderbro.core.domain.enums;

/** Health status of a monitored component, surfaced by {@code GET /api/status}. */
public enum ComponentStatus {
    OK,
    DEGRADED,
    DOWN
}