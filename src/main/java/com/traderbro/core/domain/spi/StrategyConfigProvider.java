package com.traderbro.core.domain.spi;

import com.traderbro.core.domain.StrategyConfig;
import java.util.List;

/**
 * Read-side access to strategy configuration. Implemented by the storage layer
 * ({@code strategy_config} table). Keeps {@code core} free of Spring Data types.
 */
public interface StrategyConfigProvider {

    /** Loads all strategy configs that are currently enabled. */
    List<StrategyConfig> loadActive();

    /** Loads a single strategy config by id, or empty. */
    java.util.Optional<StrategyConfig> findById(String id);
}