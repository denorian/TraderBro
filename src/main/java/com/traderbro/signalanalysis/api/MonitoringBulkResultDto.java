package com.traderbro.signalanalysis.api;

/** Response for a bulk monitoring operation. */
public record MonitoringBulkResultDto(int applied, int skippedUnsubscribed, int notFound) {
}