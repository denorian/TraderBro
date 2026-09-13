package com.traderbro.storage;

import com.traderbro.core.domain.Order;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.enums.OrderStatus;
import com.traderbro.core.domain.enums.OrderType;
import com.traderbro.core.domain.spi.OrderStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Order persistence against the {@code orders} table. */
@Repository
public class OrderRepository implements OrderStore {

    private static final String COLUMNS =
            "id, figi, side, type, requested_lots, limit_price, status, broker_order_id, "
                    + "filled_lots, avg_fill_price, created_at, updated_at, strategy_id, reason";

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final int moneyScale;
    private final RowMapper<Order> mapper;

    public OrderRepository(JdbcTemplate jdbc, int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.moneyScale = moneyScale;
        this.mapper = new OrderMapper();
    }

    @Override
    public void save(Order o) {
        named.update("""
                INSERT INTO orders (id, figi, side, type, requested_lots, limit_price, status,
                                    broker_order_id, filled_lots, avg_fill_price, created_at,
                                    updated_at, strategy_id, reason)
                VALUES (:id, :figi, :side, :type, :lots, :limit, :status, :brokerId, :filled,
                        :avg, :createdAt, :updatedAt, :strategyId, :reason)
                """, params(o));
    }

    @Override
    public void update(Order o) {
        named.update("""
                UPDATE orders SET side=:side, type=:type, requested_lots=:lots, limit_price=:limit,
                       status=:status, broker_order_id=:brokerId, filled_lots=:filled,
                       avg_fill_price=:avg, updated_at=:updatedAt, strategy_id=:strategyId, reason=:reason
                WHERE id=:id
                """, params(o));
    }

    @Override
    public Optional<Order> findById(UUID id) {
        List<Order> rows = jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id=?", mapper, id);
        return rows.stream().findFirst();
    }

    @Override
    public List<Order> findOpen() {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE status IN ('NEW','SENT','PARTIALLY_FILLED')",
                mapper);
    }

    @Override
    public boolean exists(UUID clientOrderId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM orders WHERE id=?)", Boolean.class, clientOrderId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public int updateStatus(UUID id, OrderStatus from, OrderStatus to) {
        return jdbc.update("UPDATE orders SET status=?, updated_at=? WHERE id=? AND status=?",
                to.name(), java.sql.Timestamp.from(Instant.now()), id, from.name());
    }

    private MapSqlParameterSource params(Order o) {
        return new MapSqlParameterSource()
                .addValue("id", o.getId())
                .addValue("figi", o.getFigi())
                .addValue("side", o.getSide().name())
                .addValue("type", o.getType().name())
                .addValue("lots", o.getRequestedLots())
                .addValue("limit", o.getLimitPrice())
                .addValue("status", o.getStatus().name())
                .addValue("brokerId", o.getBrokerOrderId())
                .addValue("filled", o.getFilledLots())
                .addValue("avg", o.getAvgFillPrice())
                .addValue("createdAt", java.sql.Timestamp.from(o.getCreatedAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(o.getUpdatedAt()))
                .addValue("strategyId", o.getStrategyId())
                .addValue("reason", o.getReason());
    }

    private final class OrderMapper implements RowMapper<Order> {
        @Override
        public Order mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal limit = rs.getBigDecimal("limit_price");
            BigDecimal avg = rs.getBigDecimal("avg_fill_price");
            return Order.builder()
                    .id(rs.getObject("id", UUID.class))
                    .figi(rs.getString("figi"))
                    .side(OrderSide.valueOf(rs.getString("side")))
                    .type(OrderType.valueOf(rs.getString("type")))
                    .requestedLots(rs.getLong("requested_lots"))
                    .limitPrice(limit == null ? null : limit.setScale(moneyScale))
                    .status(OrderStatus.valueOf(rs.getString("status")))
                    .brokerOrderId(rs.getString("broker_order_id"))
                    .filledLots(rs.getLong("filled_lots"))
                    .avgFillPrice(avg == null ? null : avg.setScale(moneyScale))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .strategyId(rs.getString("strategy_id"))
                    .reason(rs.getString("reason"))
                    .build();
        }
    }
}