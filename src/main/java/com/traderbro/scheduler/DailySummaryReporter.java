package com.traderbro.scheduler;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.SignalVerdict;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.execution.killswitch.KillSwitch;
import com.traderbro.storage.SignalRepository;
import com.traderbro.storage.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes the {@link NotificationType#DAILY_SUMMARY} digest at 19:00 Moscow: day P&amp;L, number
 * of trades/signals, open positions, exposure and the risk/stream state.
 *
 * <p>Wins/losses and futures margin are currently reported as 0 until closed-position P&amp;L and
 * futures margin aggregation are wired up (see the stage-2 A8 review item); the rest of the
 * digest reflects live state.
 */
@Slf4j
@RequiredArgsConstructor
public class DailySummaryReporter {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final PortfolioProvider portfolioProvider;
    private final SignalRepository signalRepository;
    private final TradeRepository tradeRepository;
    private final KillSwitch killSwitch;
    private final StreamHealth streamHealth;
    private final TraderEventPublisher publisher;

    @Scheduled(cron = "0 0 19 * * *", zone = "Europe/Moscow")
    public void report() {
        Instant now = Instant.now();
        LocalDate today = now.atZone(MOSCOW).toLocalDate();
        Instant dayStart = today.atStartOfDay(MOSCOW).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(MOSCOW).toInstant();

        Portfolio p = portfolioProvider.snapshot();
        List<Signal> daySignals = signalRepository.findByRange(dayStart, dayEnd);
        long accepted = daySignals.stream()
                .filter(s -> s.getVerdict() == SignalVerdict.ACCEPTED).count();
        long rejected = daySignals.size() - accepted;
        long trades = tradeRepository.countByRange(dayStart, dayEnd);
        int positions = tradeRepository.expectedShares().size();
        double exposure = exposurePct(p);

        Map<String, Object> payload = new HashMap<>();
        payload.put("date", today.toString());
        payload.put("pnl", p.getDayPnL() == null ? "0" : p.getDayPnL().toPlainString());
        payload.put("pnlPct", p.getDayPnLPercent() == null ? "0" : p.getDayPnLPercent().toPlainString());
        payload.put("trades", trades);
        payload.put("wins", 0);
        payload.put("losses", 0);
        payload.put("signals", daySignals.size());
        payload.put("accepted", accepted);
        payload.put("rejected", rejected);
        payload.put("positions", positions);
        payload.put("exposure", String.format("%.1f", exposure));
        payload.put("margin", "0");
        payload.put("marginPct", "0");
        payload.put("killSwitch", killSwitch.isActive() ? "АКТИВЕН" : "выключен");
        payload.put("stream", streamHealth.isConnected() ? "OK"
                : streamHealth.isDegraded() ? "DEGRADED" : "нет данных");
        payload.put("ts", now.atZone(MOSCOW).format(TS_FMT));

        publisher.publish(TraderEvent.of(NotificationType.DAILY_SUMMARY, NotificationLevel.INFO, payload));
        log.info("published daily summary: pnl={}, trades={}, signals={}",
                p.getDayPnL(), trades, daySignals.size());
    }

    private static double exposurePct(Portfolio p) {
        BigDecimal total = p.getTotalValue();
        BigDecimal securities = p.getSecuritiesValue();
        if (total == null || total.signum() == 0 || securities == null) {
            return 0.0;
        }
        return securities.doubleValue() / total.doubleValue() * 100.0;
    }
}