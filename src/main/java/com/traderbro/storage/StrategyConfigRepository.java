package com.traderbro.storage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.traderbro.core.domain.StrategyConfig;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Hot-reloadable strategy configuration ({@code strategy_config} table). */
@Repository
public class StrategyConfigRepository implements StrategyConfigProvider {

    private static final String COLUMNS =
            "id, enabled, params, order_type, max_position_pct, interval, version, instrument_types";

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int moneyScale;
    private final RowMapper<StrategyConfig> mapper;

    public StrategyConfigRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, @Value("${app.money-scale}") int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
        this.moneyScale = moneyScale;
        this.mapper = new ConfigMapper();
    }

    @Override
    public List<StrategyConfig> loadActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM strategy_config WHERE enabled=TRUE", mapper);
    }

    @Override
    public Optional<StrategyConfig> findById(String id) {
        List<StrategyConfig> rows = jdbc.query("SELECT " + COLUMNS + " FROM strategy_config WHERE id=?", mapper, id);
        return rows.stream().findFirst();
    }

    /** Inserts or updates (by id) a strategy config row; used for initial seeding. */
    public void upsert(StrategyConfig cfg) {
        named.update("""
                INSERT INTO strategy_config (id, enabled, params, order_type, max_position_pct, interval, version, instrument_types, updated_at)
                VALUES (:id, :enabled, CAST(:params AS jsonb), :orderType, :maxPct, :interval, :version, CAST(:instrumentTypes AS text[]), :updatedAt)
                ON CONFLICT (id) DO UPDATE SET enabled=EXCLUDED.enabled, params=EXCLUDED.params,
                    order_type=EXCLUDED.order_type, max_position_pct=EXCLUDED.max_position_pct,
                    interval=EXCLUDED.interval, instrument_types=EXCLUDED.instrument_types,
                    version=strategy_config.version+1,
                    updated_at=EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", cfg.getId())
                .addValue("enabled", cfg.isEnabled())
                .addValue("params", toJson(cfg.getParams()))
                .addValue("orderType", cfg.getOrderType().name())
                .addValue("maxPct", cfg.getMaxPositionPct())
                .addValue("interval", cfg.getInterval().name())
                .addValue("instrumentTypes", instrumentTypesToCsv(cfg.getInstrumentTypes()))
                .addValue("version", cfg.getVersion())
                .addValue("updatedAt", java.sql.Timestamp.from(Instant.now())));
    }

    private String instrumentTypesToCsv(List<InstrumentType> types) {
        if (types == null || types.isEmpty()) {
            return null;
        }
        return types.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize strategy params", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            return raw.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse strategy params JSON", e);
        }
    }

    private final class ConfigMapper implements RowMapper<StrategyConfig> {
        @Override
        public StrategyConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            return StrategyConfig.builder()
                    .id(rs.getString("id"))
                    .enabled(rs.getBoolean("enabled"))
                    .params(fromJson(rs.getString("params")))
                    .orderType(OrderType.valueOf(rs.getString("order_type")))
                    .maxPositionPct(rs.getBigDecimal("max_position_pct").setScale(moneyScale))
                    .interval(CandleInterval.valueOf(rs.getString("interval")))
                    .instrumentTypes(instrumentTypes(rs))
                    .version(rs.getLong("version"))
                    .build();
        }

        /** Maps the nullable {@code TEXT[]} instrument_types column to a list, or null when unset. */
        private List<InstrumentType> instrumentTypes(ResultSet rs) throws SQLException {
            java.sql.Array arr = rs.getArray("instrument_types");
            if (arr == null) {
                return null;
            }
            Object[] raw = (Object[]) arr.getArray();
            if (raw.length == 0) {
                return null;
            }
            List<InstrumentType> types = new ArrayList<>(raw.length);
            for (Object o : raw) {
                types.add(InstrumentType.valueOf((String) o));
            }
            return types;
        }
    }
}