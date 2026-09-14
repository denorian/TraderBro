package com.traderbro.notify.telegram;

import com.traderbro.core.event.TraderEvent;

/** Renders a {@link TraderEvent} into an HTML Telegram message. */
public interface TelegramMessageFormatter {

    String format(TraderEvent event);
}