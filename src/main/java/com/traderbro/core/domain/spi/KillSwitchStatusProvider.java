package com.traderbro.core.domain.spi;

/**
 * Read-only view of the kill-switch, exposed so the notification layer can report its state
 * without depending on the {@code execution} package.
 */
public interface KillSwitchStatusProvider {

    boolean isActive();

    String reason();
}