package com.traderbro.signalanalysis.api;

import java.util.List;

/** Request body for PATCH /api/signal-analysis/channels/monitoring (bulk). */
public record MonitoringBulkRequest(boolean enabled, List<String> channelIds) {
}