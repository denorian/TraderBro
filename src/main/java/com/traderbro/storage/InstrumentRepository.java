package com.traderbro.storage;

import com.traderbro.core.domain.Instrument;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reference-data cache for instruments ({@code instruments} table). */
@Repository
public class InstrumentRepository {

    private static final String UPSERT = """
            INSERT INTO instruments (figi, ticker, name, isin, currency, lot, min_price_increment,
                                     board, tradable, updated_at)
            VALUES (:figi, :ticker, :name, :isin, :currency, :lot, :minStep, :board, :tradable, :updatedAt)
            ON CONFLICT (figi) DO UPDATE SET ticker=EXCLUDED.ticker, name=EXCLUDED.name,
                isin=EXCLUDED.isin, currency=EXCLUDED.currency, lot=EXCLUDED.lot,
                min_price_increment=EXCLUDED.min_price_increment, board=EXCLUDED.board,
                tradable=EXCLUDED.tradable, updated_at=EXCLUDED.updated_at
            """;

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final int moneyScale;
    private final RowMapper<Instrument> mapper;

    public InstrumentRepository(JdbcTemplate jdbc, int moneyScale) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.moneyScale = moneyScale;
        this.mapper = new InstrumentMapper();
    }

    public void upsertAll(List<Instrument> instruments) {
        MapSqlParameterSource[] batch = instruments.stream()
                .map(i -> new MapSqlParameterSource()
                        .addValue("figi", i.getFigi())
                        .addValue("ticker", i.getTicker())
                        .addValue("name", i.getName())
                        .addValue("isin", i.getIsin())
                        .addValue("currency", i.getCurrency())
                        .addValue("lot", i.getLot())
                        .addValue("minStep", i.getMinPriceIncrement())
                        .addValue("board", i.getBoard())
                        .addValue("tradable", i.isTradable())
                        .addValue("updatedAt", java.sql.Timestamp.from(Instant.now())))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
    }

    public List<Instrument> findByTickerIn(List<String> tickers) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tickers", tickers);
        return named.query("""
                SELECT figi, ticker, name, isin, currency, lot, min_price_increment, board, tradable
                FROM instruments WHERE ticker IN (:tickers)
                """, params, mapper);
    }

    /** Lists all tradable instruments (used by the signal engine and schedulers). */
    public List<Instrument> findAllTradable() {
        return jdbc.query("""
                SELECT figi, ticker, name, isin, currency, lot, min_price_increment, board, tradable
                FROM instruments WHERE tradable=TRUE ORDER BY ticker
                """, mapper);
    }

    public Optional<Instrument> findByFigi(String figi) {
        List<Instrument> rows = jdbc.query("""
                SELECT figi, ticker, name, isin, currency, lot, min_price_increment, board, tradable
                FROM instruments WHERE figi=?
                """, mapper, figi);
        return rows.stream().findFirst();
    }

    private final class InstrumentMapper implements RowMapper<Instrument> {
        @Override
        public Instrument mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal minStep = rs.getBigDecimal("min_price_increment");
            return Instrument.builder()
                    .figi(rs.getString("figi"))
                    .ticker(rs.getString("ticker"))
                    .name(rs.getString("name"))
                    .isin(rs.getString("isin"))
                    .currency(rs.getString("currency"))
                    .lot(rs.getInt("lot"))
                    .minPriceIncrement(minStep == null ? BigDecimal.ONE.setScale(moneyScale) : minStep.setScale(moneyScale))
                    .board(rs.getString("board"))
                    .tradable(rs.getBoolean("tradable"))
                    .build();
        }
    }
}