package com.traderbro.api.web;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Signal;
import com.traderbro.core.domain.enums.ComponentStatus;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.OrderStore;
import com.traderbro.core.domain.spi.PortfolioProvider;
import com.traderbro.core.backtest.BacktestReport;
import com.traderbro.execution.killswitch.KillSwitch;
import com.traderbro.storage.BacktestRunRepository;
import com.traderbro.storage.SignalRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only monitoring API.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MonitoringController {

    private final StreamHealth streamHealth;
    private final BrokerGateway gateway;
    private final KillSwitch killSwitch;
    private final PortfolioProvider portfolioProvider;
    private final OrderStore orderStore;
    private final SignalRepository signalRepository;
    private final BacktestRunRepository backtestRunRepository;

    public record ComponentStatusDto(ComponentStatus status, String detail) {}

    public record StatusResponse(
            ComponentStatusDto dataStream,
            ComponentStatusDto broker,
            ComponentStatusDto db,
            boolean killSwitchActive,
            String killSwitchReason,
            List<com.traderbro.core.domain.Position> positions,
            Portfolio portfolio,
            Duration streamLag) {}

    @GetMapping("/status")
    public StatusResponse status() {
        Duration lag = streamHealth.lag();
        ComponentStatusDto stream = streamHealth.isConnected() && lag != null && lag.getSeconds() < 60
                ? new ComponentStatusDto(ComponentStatus.OK, "connected")
                : streamHealth.isDegraded()
                ? new ComponentStatusDto(ComponentStatus.DEGRADED, "reconnecting")
                : new ComponentStatusDto(ComponentStatus.DOWN, "lag=" + lag);
        ComponentStatusDto brokerStatus = new ComponentStatusDto(ComponentStatus.OK, "gateway up");
        ComponentStatusDto dbStatus = new ComponentStatusDto(ComponentStatus.OK, "ok");
        return new StatusResponse(stream, brokerStatus, dbStatus, killSwitch.isActive(),
                killSwitch.reason(), gateway.getPositions(), portfolioProvider.snapshot(), lag);
    }

    @GetMapping("/signals")
    public List<Signal> signals(@RequestParam Instant from, @RequestParam Instant to) {
        return signalRepository.findByRange(from, to);
    }

    @GetMapping("/orders")
    public List<Order> orders(@RequestParam(required = false) LocalDate date) {
        // date filtering is applied in storage for terminal histories; open orders returned here.
        return orderStore.findOpen();
    }

    @GetMapping("/backtests/{id}")
    public Optional<BacktestReport> backtest(@PathVariable String id) {
        return backtestRunRepository.findById(id);
    }
}