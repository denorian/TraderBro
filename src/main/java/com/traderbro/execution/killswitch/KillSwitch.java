package com.traderbro.execution.killswitch;

import java.time.Instant;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Emergency trading halt. Once active it blocks new orders (enforced by the RiskGate via
 * {@code RiskContext.killSwitchActive} and directly by {@code OrderManager}) and cancels open
 * orders. Reactivation is only manual, with confirmation.
 *
 * <p>Liquidation is delegated to {@code OrderManager} (the only component allowed to place
 * orders) through the {@code onActivate} callback wired in the composition root.
 */
@Slf4j
public class KillSwitch {

    private volatile boolean active = false;
    private volatile String reason = null;
    private volatile Instant activatedAt = null;
    private volatile Instant deactivatedAt = null;

    private volatile Consumer<String> onActivate = r -> { };
    private volatile Runnable onDeactivate = () -> { };

    /** Wires the activation side-effects (cancel orders, optionally liquidate). */
    public void setOnActivate(Consumer<String> onActivate) {
        this.onActivate = onActivate;
    }

    /** Wires the deactivation side-effect (resume). */
    public void setOnDeactivate(Runnable onDeactivate) {
        this.onDeactivate = onDeactivate;
    }

    /** Activates the kill-switch. Returns false if it was already active. */
    public synchronized boolean activate(String reason) {
        if (active) {
            log.warn("kill-switch already active; ignoring activation request");
            return false;
        }
        this.active = true;
        this.reason = reason;
        this.activatedAt = Instant.now();
        this.deactivatedAt = null;
        log.error("KILL-SWITCH ACTIVATED: {}", reason);
        onActivate.accept(reason);
        return true;
    }

    /**
     * Deactivates the kill-switch. Only manual; requires confirmation.
     *
     * @return false when not confirmed or not active
     */
    public synchronized boolean deactivate(boolean confirm) {
        if (!active) {
            log.info("kill-switch is not active; nothing to deactivate");
            return false;
        }
        if (!confirm) {
            log.warn("kill-switch deactivation rejected: confirmation required");
            return false;
        }
        this.active = false;
        this.deactivatedAt = Instant.now();
        log.info("KILL-SWITCH DEACTIVATED (manual, confirmed)");
        onDeactivate.run();
        return true;
    }

    public boolean isActive() {
        return active;
    }

    public String reason() {
        return reason;
    }

    public Instant activatedAt() {
        return activatedAt;
    }
}