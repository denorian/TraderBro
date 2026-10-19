package com.traderbro.storage;

import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for {@code angulation_events}. Idempotent by natural key. */
@Repository
public class AngulationEventRepository {

    private static final String COLS = """
            figi, interval, ts, divergence_event_id, direction, price_slope_atr,
            jaw_slope_atr, angle_atr, anchor_ts, bars_in_window, created_at
            """;

    private static final String UPSERT = """
            INSERT INTO angulation_events
                (figi, interval, ts, divergence_event_id, direction, price_slope_atr,
                 jaw_slope_atr, angle_atr, anchor_ts, bars_in_window, created_at)
            VALUES (:figi, :interval, :ts, :divergenceEventId, :direction, :priceSlopeAtr,
                    :jawSlopeAtr, :angleAtr, :anchorTs, :barsInWindow, :createdAt)
            ON CONFLICT (figi, interval, ts, divergence_event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final RowMapper<AngulationEvent> mapper = new AngulationMapper();

    public AngulationEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void upsertAll(List<AngulationEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("figi", e.getFigi())
                        .addValue("interval", e.getInterval().name())
                        .addValue("ts", Timestamp.from(e.getTs()))
                        .addValue("divergenceEventId", e.getDivergenceEventId())
                        .addValue("direction", e.getDirection().name())
                        .addValue("priceSlopeAtr", e.getPriceSlopeAtr())
                        .addValue("jawSlopeAtr", e.getJawSlopeAtr())
                        .addValue("angleAtr", e.getAngleAtr())
                        .addValue("anchorTs", Timestamp.from(e.getAnchorTs()))
                        .addValue("barsInWindow", e.getBarsInWindow())
                        .addValue("createdAt", Timestamp.from(e.getCreatedAt())))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
    }

    public List<AngulationEvent> find(String figi, CandleInterval interval, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM angulation_events WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (figi != null) {
            sql.append(" AND figi = ?");
            args.add(figi);
        }
        if (interval != null) {
            sql.append(" AND interval = ?");
            args.add(interval.name());
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(Math.min(limit, 500));
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public List<AngulationEvent> findByDivergence(UUID divergenceEventId) {
        return jdbc.query("SELECT " + COLS
                        + " FROM angulation_events WHERE divergence_event_id = ? ORDER BY ts DESC",
                mapper, divergenceEventId);
    }

    private static final class AngulationMapper implements RowMapper<AngulationEvent> {
        @Override
        public AngulationEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return AngulationEvent.builder()
                    .figi(rs.getString("figi"))
                    .interval(CandleInterval.valueOf(rs.getString("interval")))
                    .ts(rs.getTimestamp("ts").toInstant())
                    .divergenceEventId(rs.getObject("divergence_event_id", UUID.class))
                    .direction(AngulationEvent.Direction.valueOf(rs.getString("direction")))
                    .priceSlopeAtr(rs.getDouble("price_slope_atr"))
                    .jawSlopeAtr(rs.getDouble("jaw_slope_atr"))
                    .angleAtr(rs.getDouble("angle_atr"))
                    .anchorTs(rs.getTimestamp("anchor_ts").toInstant())
                    .barsInWindow(rs.getInt("bars_in_window"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}