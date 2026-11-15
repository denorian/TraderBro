package com.traderbro.signalanalysis.api;

/** Request body for PATCH /api/signal-analysis/channels/{id}/monitoring. */
public record MonitoringUpdateRequest(boolean enabled) {
}