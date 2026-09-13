package com.traderbro.core.strategies;

import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.core.domain.spi.SignalFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry of active strategies and their parameters, backed by {@code strategy_config}.
 * Reloaded on a schedule without restart. Invalid parameter sets disable the strategy and
 * emit an audit event via the supplied sink.
 */
@Slf4j
@RequiredArgsConstructor
public class StrategyRegistry {

    private final StrategyConfigProvider configProvider;
    private final Map<String, TradingStrategy> factories = new ConcurrentHashMap<>();
    private final Map<String, StrategyConfig> active = new ConcurrentHashMap<>();
    private final SignalFilter signalFilter;
    private final Consumer<String> auditSink;
    private volatile Instant lastReloadAt;

    /** Registers a strategy factory (id → instance). */
    public void register(TradingStrategy strategy) {
        factories.put(strategy.id(), strategy);
    }

    /** Reloads config from storage, enabling/disabling strategies accordingly. */
    public synchronized void reload() {
        Map<String, StrategyConfig> next = new LinkedHashMap<>();
        for (StrategyConfig cfg : configProvider.loadActive()) {
            TradingStrategy factory = factories.get(cfg.getId());
            if (factory == null) {
                auditSink.accept("strategy_config: unknown strategy id=" + cfg.getId() + " ignored");
                continue;
            }
            try {
                // Validate parameters by attempting a dry build on an empty series is not
                // possible without bars; instead validate declared parameter invariants here.
                validateParams(factory, cfg);
                next.put(cfg.getId(), cfg);
            } catch (IllegalArgumentException e) {
                auditSink.accept("strategy_config: strategy=" + cfg.getId()
                        + " disabled, invalid params: " + e.getMessage());
                log.warn("Strategy {} disabled due to invalid params: {}", cfg.getId(), e.getMessage());
            }
        }
        active.clear();
        active.putAll(next);
        lastReloadAt = Instant.now();
        log.info("StrategyRegistry reloaded: {} active strategies", active.size());
    }

    private void validateParams(TradingStrategy factory, StrategyConfig cfg) {
        if (cfg.getMaxPositionPct() == null
                || cfg.getMaxPositionPct().signum() <= 0
                || cfg.getMaxPositionPct().doubleValue() > 1.0) {
            throw new IllegalArgumentException("maxPositionPct must be in (0,1]");
        }
        if (factory.id().equals(SmaCrossStrategy.ID)) {
            int fast = cfg.getParams().get("fast") == null ? 20 : Integer.parseInt(cfg.getParams().get("fast"));
            int slow = cfg.getParams().get("slow") == null ? 100 : Integer.parseInt(cfg.getParams().get("slow"));
            if (fast >= slow) {
                throw new IllegalArgumentException("fast must be < slow");
            }
        }
        if (factory.id().equals(EmaMomentumStrategy.ID)) {
            int fast = cfg.getParams().get("fastEm") == null ? 12 : Integer.parseInt(cfg.getParams().get("fastEm"));
            int slow = cfg.getParams().get("slowEm") == null ? 26 : Integer.parseInt(cfg.getParams().get("slowEm"));
            if (fast >= slow) {
                throw new IllegalArgumentException("fastEm must be < slowEm");
            }
        }
        // Additional per-strategy invariants may be added here.
    }

    /** The currently active strategies. */
    public List<StrategyConfig> activeConfigs() {
        List<StrategyConfig> list = new ArrayList<>(active.values());
        return Collections.unmodifiableList(list);
    }

    /** Strategy factory by id, or null. */
    public TradingStrategy factory(String id) {
        return factories.get(id);
    }

    /** Active strategy config by id, or null when disabled. */
    public StrategyConfig active(String id) {
        return active.get(id);
    }

    /** Whether the given strategy is currently enabled and valid. */
    public boolean isActive(String id) {
        return active.containsKey(id);
    }

    public Instant lastReloadAt() {
        return lastReloadAt;
    }

    public SignalFilter signalFilter() {
        return signalFilter;
    }
}