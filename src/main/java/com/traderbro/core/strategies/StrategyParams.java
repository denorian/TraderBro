package com.traderbro.core.strategies;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import lombok.Value;

/**
 * Strategy parameters read from the {@code strategy_config} JSONB blob.
 * Typed accessors fall back to defaults for missing keys; invalid values are handled by
 * {@link StrategyRegistry}, which disables the strategy and writes an audit event.
 */
@Value
public class StrategyParams {

    Map<String, String> values;

    public StrategyParams(Map<String, String> values) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(values);
    }

    public String getString(String key, String defaultValue) {
        String v = values.get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }
}