package com.traderbro.storage;

import com.traderbro.core.domain.Trade;
import com.traderbro.core.domain.enums.OrderSide;
import com.traderbro.core.domain.spi.PositionLedger;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Executed trades persistence; also serves as the {@link PositionLedger} source of truth. */
@Repository
public class TradeRepository implements PositionLedger {

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final int moneyScale;

    public TradeRepository(JdbcTemplate jdbc, int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.moneyScale = moneyScale;
    }

    public void insert(Trade t) {
        named.update("""
                INSERT INTO trades (id, order_id, figi, side, lots, price, ts, commission)
                VALUES (:id, :orderId, :figi, :side, :lots, :price, :ts, :commission)
                """, new MapSqlParameterSource()
                .addValue("id", t.getId())
                .addValue("orderId", t.getOrderId())
                .addValue("figi", t.getFigi())
                .addValue("side", t.getSide().name())
                .addValue("lots", t.getLots())
                .addValue("price", t.getPrice())
                .addValue("ts", java.sql.Timestamp.from(t.getTs()))
                .addValue("commission", t.getCommission() == null ? BigDecimal.ZERO : t.getCommission()));
    }

    @Override
    public Map<String, Long> expectedShares() {
        Map<String, Long> shares = new HashMap<>();
        jdbc.query("SELECT figi, side, lots FROM trades", rs -> {
            String figi = rs.getString("figi");
            OrderSide side = OrderSide.valueOf(rs.getString("side"));
            long signed = side == OrderSide.BUY ? rs.getLong("lots") : -rs.getLong("lots");
            shares.merge(figi, signed, Long::sum);
        });
        return shares;
    }
}