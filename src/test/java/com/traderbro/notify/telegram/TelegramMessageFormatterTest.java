package com.traderbro.notify.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramMessageFormatterTest {

    private final DefaultTelegramMessageFormatter formatter = new DefaultTelegramMessageFormatter();

    private TraderEvent signalEntry() {
        Map<String, Object> p = new HashMap<>();
        p.put("ticker", "SBER");
        p.put("typeLabel", "акция");
        p.put("strategy", "ema-momentum-15m");
        p.put("price", new BigDecimal("301.25"));
        p.put("qty", 30);
        p.put("qtyUnit", "лот");
        p.put("notional", new BigDecimal("90375"));
        p.put("pct", new BigDecimal("9.8"));
        p.put("stop", new BigDecimal("296.10"));
        p.put("stopPct", new BigDecimal("-1.71"));
        p.put("indicators", "EMA12=302.10 > EMA26=299.84, RSI=58");
        return TraderEvent.of(NotificationType.SIGNAL_ENTRY, NotificationLevel.INFO, p);
    }

    @Test
    void rendersSignalEntryWithoutLeftoverPlaceholders() {
        String html = formatter.format(signalEntry());
        assertThat(html).doesNotContain("${");
        assertThat(html).contains("СИГНАЛ: ВХОД 📈 LONG");
        assertThat(html).contains("<b>SBER</b>");
        assertThat(html).contains("ema-momentum-15m");
        assertThat(html).contains("301.25");
        assertThat(html).contains("МСК");
    }

    @Test
    void rendersKillSwitchCriticalWithMarker() {
        TraderEvent e = TraderEvent.of(NotificationType.KILL_SWITCH_ACTIVATED,
                NotificationLevel.CRITICAL, Map.of("reason", "дневной убыток превысил лимит",
                        "cancelledOrders", 3, "liquidated", "не закрывались", "closePositions", false,
                        "positions", "SBER 30 лот"));
        String html = formatter.format(e);
        assertThat(html).doesNotContain("${");
        assertThat(html).startsWith("🔴");
        assertThat(html).contains("KILL-SWITCH АКТИВИРОВАН");
    }

    @Test
    void replacesMissingValuesWithDash() {
        TraderEvent e = TraderEvent.of(NotificationType.DAILY_SUMMARY, NotificationLevel.INFO, Map.of());
        String html = formatter.format(e);
        assertThat(html).doesNotContain("${");
        assertThat(html).contains("—");
    }
}