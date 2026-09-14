package com.traderbro.core.event;

/**
 * All notification event types produced across the trading loop. This enum lives in
 * {@code core} so that {@code notify} can reference it while {@code core} stays free of any
 * Telegram/delivery knowledge.
 */
public enum NotificationType {
    SIGNAL_ENTRY,
    SIGNAL_EXIT,
    SIGNAL_REJECTED,
    ORDER_FILLED,
    ORDER_PARTIALLY_FILLED,
    ORDER_CANCELLED,
    ORDER_REJECTED,
    ORDER_EXPIRED,
    POSITION_OPENED,
    POSITION_CLOSED,
    STOP_LOSS_TRIGGERED,
    RISK_LIMIT_BREACH,
    KILL_SWITCH_ACTIVATED,
    KILL_SWITCH_DEACTIVATED,
    RECONCILE_MISMATCH,
    DATA_STREAM_DOWN,
    DATA_STREAM_RESTORED,
    DAILY_SUMMARY,
    FUTURES_ROLLOVER_WARNING,
    FUTURES_EXPIRY_LOCK,
    BACKTEST_COMPLETED,
    APP_STARTED,
    APP_STOPPED,
    ERROR
}