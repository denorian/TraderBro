package com.traderbro.storage;

import com.traderbro.core.domain.Portfolio;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes portfolio valuation snapshots ({@code portfolio_snapshots} table). */
@Repository
public class PortfolioSnapshotRepository {

    private final NamedParameterJdbcTemplate named;

    public PortfolioSnapshotRepository(JdbcTemplate jdbc) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void insert(Portfolio p) {
        named.update("""
                INSERT INTO portfolio_snapshots (ts, total_value, cash, securities_value, day_pnl, day_pnl_percent)
                VALUES (:ts, :total, :cash, :securities, :dayPnl, :dayPnlPct)
                """, new MapSqlParameterSource()
                .addValue("ts", java.sql.Timestamp.from(p.getAt()))
                .addValue("total", p.getTotalValue())
                .addValue("cash", p.getCash() == null ? java.math.BigDecimal.ZERO : p.getCash())
                .addValue("securities", p.getSecuritiesValue() == null
                        ? java.math.BigDecimal.ZERO : p.getSecuritiesValue())
                .addValue("dayPnl", p.getDayPnL() == null ? java.math.BigDecimal.ZERO : p.getDayPnL())
                .addValue("dayPnlPct", p.getDayPnLPercent() == null
                        ? java.math.BigDecimal.ZERO : p.getDayPnLPercent()));
    }
}