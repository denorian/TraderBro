package com.traderbro.notify.telegram;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.KillSwitchStatusProvider;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.storage.SignalRepository;
import java.util.List;

/**
 * Read-only Telegram command handling: /status, /positions, /signals [N], /killswitch, /help.
 * The chat is never a channel for risk control — activation/deactivation stays REST-only.
 */
public class TelegramCommandHandler {

    private final PortfolioProvider portfolioProvider;
    private final BrokerGateway gateway;
    private final KillSwitchStatusProvider killSwitch;
    private final StreamHealth streamHealth;
    private final SignalRepository signalRepository;
    private final OrderStore orderStore;

    public TelegramCommandHandler(PortfolioProvider portfolioProvider, BrokerGateway gateway,
                                  KillSwitchStatusProvider killSwitch, StreamHealth streamHealth,
                                  SignalRepository signalRepository, OrderStore orderStore) {
        this.portfolioProvider = portfolioProvider;
        this.gateway = gateway;
        this.killSwitch = killSwitch;
        this.streamHealth = streamHealth;
        this.signalRepository = signalRepository;
        this.orderStore = orderStore;
    }

    /** Returns the reply for a command, or null when the command is unknown. */
    public String handle(String command, String args) {
        return switch (command.toLowerCase()) {
            case "/status" -> status();
            case "/positions" -> positions();
            case "/signals" -> signals(args);
            case "/killswitch" -> killswitch();
            case "/help" -> help();
            default -> null;
        };
    }

    private String status() {
        Portfolio p = portfolioProvider.snapshot();
        return "Статус:\n"
                + "Стрим: " + streamHealth.isConnected() + " (lag " + streamHealth.lag() + ")\n"
                + "Kill-switch: " + (killSwitch.isActive() ? "АКТИВЕН (" + killSwitch.reason() + ")" : "не активен") + "\n"
                + "Портфель: " + money(p.getTotalValue()) + "\n"
                + "P&L дня: " + money(p.getDayPnL()) + " (" + pct(p.getDayPnLPercent()) + ")\n"
                + "Позиций: " + gateway.getPositions().size();
    }

    private String positions() {
        var positions = gateway.getPositions();
        if (positions.isEmpty()) {
            return "Открытых позиций нет";
        }
        StringBuilder sb = new StringBuilder("Открытые позиции:\n");
        for (var pos : positions) {
            sb.append("· ").append(pos.getFigi()).append(" ").append(pos.getShares())
                    .append(" штук, ср. ").append(pos.getAveragePrice() == null ? "—" : money(pos.getAveragePrice())).append("\n");
        }
        return sb.toString().trim();
    }

    private String signals(String args) {
        int n = 5;
        if (args != null && !args.isBlank()) {
            try {
                n = Math.max(1, Math.min(Integer.parseInt(args.trim()), 50));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        List<Signal> signals = signalRepository.findLatest(n);
        if (signals.isEmpty()) {
            return "Сигналов нет";
        }
        StringBuilder sb = new StringBuilder("Последние сигналы:\n");
        for (Signal s : signals) {
            sb.append("· ").append(s.getTs()).append(" ").append(s.getFigi()).append(" ")
                    .append(s.getStrategyId()).append(" → ").append(s.getVerdict())
                    .append(s.getReason() == null ? "" : " (" + s.getReason() + ")").append("\n");
        }
        return sb.toString().trim();
    }

    private String killswitch() {
        return killSwitch.isActive()
                ? "Kill-switch АКТИВЕН (причина: " + killSwitch.reason() + ")\n"
                        + "Деактивация: только через REST"
                : "Kill-switch не активен";
    }

    private String help() {
        return "Доступные команды:\n"
                + "/status — состояние контуров, kill-switch, P&L дня\n"
                + "/positions — открытые позиции\n"
                + "/signals [N] — последние N сигналов (по умолчанию 5)\n"
                + "/killswitch — состояние kill-switch (управление — через REST)\n"
                + "/help — справка";
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "—" : v.toPlainString() + " ₽";
    }

    private static String pct(java.math.BigDecimal v) {
        return v == null ? "—" : v.movePointRight(2).setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }
}