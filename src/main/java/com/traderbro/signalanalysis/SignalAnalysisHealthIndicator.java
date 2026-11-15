package com.traderbro.signalanalysis;

import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health contributor for Telegram ingestion. It reports the TDLib client / auth / heartbeat state
 * in the health details but never takes the overall application to DOWN: a Telegram auth or
 * ingestion problem must not bring down the REST/UI/trading parts of the same app.
 */
@Component
public class SignalAnalysisHealthIndicator implements HealthIndicator {

    private final SignalAnalysisProperties props;
    private final TelegramSessionManager session;
    private final ObjectProvider<SignalIngestionLifecycle> lifecycle;

    public SignalAnalysisHealthIndicator(SignalAnalysisProperties props,
                                         TelegramSessionManager session,
                                         ObjectProvider<SignalIngestionLifecycle> lifecycle) {
        this.props = props;
        this.session = session;
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {
        boolean enabled = props.getIngestion().isEnabled();
        var auth = session.get();
        SignalIngestionLifecycle lc = lifecycle.getIfAvailable();
        boolean active = lc != null && lc.isActive();

        Health.Builder b = (!enabled || !auth.isReady() || !active)
                ? Health.unknown() : Health.up();
        return b.withDetail("enabled", enabled)
                .withDetail("authorizationState", auth)
                .withDetail("ingestionActive", active)
                .withDetail("lastHeartbeatAt", lc == null ? null : lc.lastHeartbeat())
                .withDetail("lastDiscoveryAt", lc == null ? null : lc.lastDiscoveryAt())
                .build();
    }
}