package com.traderbro.storage;

import com.traderbro.core.domain.Instrument;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reference-data cache for instruments ({@code instruments} table). */
@Repository
public class InstrumentRepository {

    private static final String COLS =
            "figi, ticker, name, isin, currency, lot, min_price_increment, board, tradable, "
                    + "instrument_type, basic_asset, min_price_increment_amount, "
                    + "expiration_date, first_trade_date, initial_margin, "
                    + "base_asset, quote_asset, tick_size, step_size, min_notional, onboard_date, "
                    + "market_cap_usd, market_cap_rank";

    private static final String UPSERT = """
            INSERT INTO instruments (figi, ticker, name, isin, currency, lot, min_price_increment,
                                     board, tradable, updated_at, instrument_type, basic_asset,
                                     min_price_increment_amount, expiration_date, first_trade_date,
                                     initial_margin, base_asset, quote_asset, tick_size, step_size,
                                     min_notional, onboard_date, market_cap_usd, market_cap_rank)
            VALUES (:figi, :ticker, :name, :isin, :currency, :lot, :minStep, :board, :tradable,
                    :updatedAt, :instrumentType, :basicAsset, :minStepAmount, :expirationDate,
                    :firstTradeDate, :initialMargin, :baseAsset, :quoteAsset, :tickSize, :stepSize,
                    :minNotional, :onboardDate, :marketCapUsd, :marketCapRank)
            ON CONFLICT (figi) DO UPDATE SET ticker=EXCLUDED.ticker, name=EXCLUDED.name,
                isin=EXCLUDED.isin, currency=EXCLUDED.currency, lot=EXCLUDED.lot,
                min_price_increment=EXCLUDED.min_price_increment, board=EXCLUDED.board,
                tradable=EXCLUDED.tradable, updated_at=EXCLUDED.updated_at,
                instrument_type=EXCLUDED.instrument_type, basic_asset=EXCLUDED.basic_asset,
                min_price_increment_amount=EXCLUDED.min_price_increment_amount,
                expiration_date=EXCLUDED.expiration_date, first_trade_date=EXCLUDED.first_trade_date,
                initial_margin=EXCLUDED.initial_margin,
                base_asset=EXCLUDED.base_asset, quote_asset=EXCLUDED.quote_asset,
                tick_size=EXCLUDED.tick_size, step_size=EXCLUDED.step_size,
                min_notional=EXCLUDED.min_notional, onboard_date=EXCLUDED.onboard_date,
                market_cap_usd=EXCLUDED.market_cap_usd, market_cap_rank=EXCLUDED.market_cap_rank
            """;

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final int moneyScale;
    private final RowMapper<Instrument> mapper;

    public InstrumentRepository(JdbcTemplate jdbc, @Value("${app.money-scale}") int moneyScale) {
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
                        .addValue("updatedAt", java.sql.Timestamp.from(Instant.now()))
                        .addValue("instrumentType", i.getInstrumentType().name())
                        .addValue("basicAsset", i.getFutureSpec() == null ? null : i.getFutureSpec().getBasicAsset())
                        .addValue("minStepAmount", i.getFutureSpec() == null ? null : i.getFutureSpec().getMinPriceIncrementAmount())
                        .addValue("expirationDate", i.getFutureSpec() == null || i.getFutureSpec().getExpirationDate() == null
                                ? null : java.sql.Date.valueOf(i.getFutureSpec().getExpirationDate()))
                        .addValue("firstTradeDate", i.getFutureSpec() == null || i.getFutureSpec().getFirstTradeDate() == null
                                ? null : java.sql.Date.valueOf(i.getFutureSpec().getFirstTradeDate()))
                        .addValue("initialMargin", i.getFutureSpec() == null ? null : i.getFutureSpec().getInitialMargin())
                        .addValue("baseAsset", i.getCryptoSpec() == null ? null : i.getCryptoSpec().getBaseAsset())
                        .addValue("quoteAsset", i.getCryptoSpec() == null ? null : i.getCryptoSpec().getQuoteAsset())
                        .addValue("tickSize", i.getCryptoSpec() == null ? null : i.getCryptoSpec().getTickSize())
                        .addValue("stepSize", i.getCryptoSpec() == null ? null : i.getCryptoSpec().getStepSize())
                        .addValue("minNotional", i.getCryptoSpec() == null ? null : i.getCryptoSpec().getMinNotional())
                        .addValue("onboardDate", i.getCryptoSpec() == null || i.getCryptoSpec().getOnboardDate() == null
                                ? null : java.sql.Date.valueOf(i.getCryptoSpec().getOnboardDate()))
                        .addValue("marketCapUsd", i.getMarketCapUsd())
                        .addValue("marketCapRank", i.getMarketCapRank()))
                .toArray(MapSqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
    }

    /** Refreshes the market-cap metadata for a crypto instrument (CoinGecko daily job). */
    public void updateMarketCap(String figi, java.math.BigDecimal capUsd, Integer rank) {
        named.update("UPDATE instruments SET market_cap_usd=:cap, market_cap_rank=:rank, "
                        + "market_cap_updated_at=:at WHERE figi=:figi",
                new MapSqlParameterSource()
                        .addValue("cap", capUsd)
                        .addValue("rank", rank)
                        .addValue("at", java.sql.Timestamp.from(Instant.now()))
                        .addValue("figi", figi));
    }

    public List<Instrument> findByTickerIn(List<String> tickers) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tickers", tickers);
        return named.query("SELECT " + COLS + " FROM instruments WHERE ticker IN (:tickers)", params, mapper);
    }

    /** Lists all tradable instruments (used by the signal engine and schedulers). */
    public List<Instrument> findAllTradable() {
        return jdbc.query("SELECT " + COLS + " FROM instruments WHERE tradable=TRUE ORDER BY ticker", mapper);
    }

    public Optional<Instrument> findByFigi(String figi) {
        List<Instrument> rows = jdbc.query("SELECT " + COLS + " FROM instruments WHERE figi=?", mapper, figi);
        return rows.stream().findFirst();
    }

    /** Finds a single instrument by ticker (used to resolve UI chart paths by ticker). */
    public Optional<Instrument> findByTicker(String ticker) {
        List<Instrument> rows = jdbc.query("SELECT " + COLS + " FROM instruments WHERE ticker=? LIMIT 1",
                mapper, ticker);
        return rows.stream().findFirst();
    }

    private final class InstrumentMapper implements RowMapper<Instrument> {
        @Override
        public Instrument mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal minStep = rs.getBigDecimal("min_price_increment");
            BigDecimal minStepValue = minStep == null
                    ? BigDecimal.ONE.setScale(moneyScale) : minStep.setScale(moneyScale);
            String type = rs.getString("instrument_type");
            com.traderbro.core.domain.enums.InstrumentType instrumentType =
                    com.traderbro.core.domain.enums.InstrumentType.valueOf(type == null ? "SHARE" : type);

            Instrument.InstrumentBuilder b = Instrument.builder()
                    .figi(rs.getString("figi"))
                    .ticker(rs.getString("ticker"))
                    .name(rs.getString("name"))
                    .isin(rs.getString("isin"))
                    .currency(rs.getString("currency"))
                    .lot(rs.getInt("lot"))
                    .minPriceIncrement(minStepValue)
                    .board(rs.getString("board"))
                    .tradable(rs.getBoolean("tradable"))
                    .marketCapUsd(rs.getBigDecimal("market_cap_usd"))
                    .marketCapRank((Integer) rs.getObject("market_cap_rank"))
                    .instrumentType(instrumentType);

            if (instrumentType == com.traderbro.core.domain.enums.InstrumentType.FUTURE) {
                BigDecimal tickAmount = rs.getBigDecimal("min_price_increment_amount");
                BigDecimal margin = rs.getBigDecimal("initial_margin");
                java.sql.Date exp = rs.getDate("expiration_date");
                java.sql.Date first = rs.getDate("first_trade_date");
                com.traderbro.core.domain.FutureSpec spec = com.traderbro.core.domain.FutureSpec.builder()
                        .figi(rs.getString("figi"))
                        .ticker(rs.getString("ticker"))
                        .basicAsset(rs.getString("basic_asset"))
                        .lot(rs.getInt("lot"))
                        .minPriceIncrement(minStepValue)
                        .minPriceIncrementAmount(tickAmount == null
                                ? BigDecimal.ZERO.setScale(moneyScale) : tickAmount)
                        .expirationDate(exp == null ? null : exp.toLocalDate())
                        .firstTradeDate(first == null ? null : first.toLocalDate())
                        .initialMargin(margin == null ? BigDecimal.ZERO.setScale(moneyScale) : margin)
                        .build();
                b.futureSpec(spec);
            } else if (instrumentType == com.traderbro.core.domain.enums.InstrumentType.CRYPTO) {
                java.sql.Date onboard = rs.getDate("onboard_date");
                com.traderbro.core.domain.CryptoSpec spec = com.traderbro.core.domain.CryptoSpec.builder()
                        .figi(rs.getString("figi"))
                        .ticker(rs.getString("ticker"))
                        .baseAsset(rs.getString("base_asset"))
                        .quoteAsset(rs.getString("quote_asset"))
                        .tickSize(rs.getBigDecimal("tick_size"))
                        .stepSize(rs.getBigDecimal("step_size"))
                        .minNotional(rs.getBigDecimal("min_notional"))
                        .onboardDate(onboard == null ? null : onboard.toLocalDate())
                        .build();
                b.cryptoSpec(spec);
            }
            return b.build();
        }
    }
}