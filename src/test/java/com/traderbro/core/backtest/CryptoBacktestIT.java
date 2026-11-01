package com.traderbro.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.CryptoSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.indicators.SeriesFactory;
import com.traderbro.core.strategies.EmaMomentumStrategy;
import com.traderbro.core.strategies.StrategyParams;
import com.traderbro.storage.BarRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the crypto data path (review B1/B4): crypto bars persisted via
 * {@link BarRepository} round-trip as final bars (the stream now persists only closed candles)
 * and a real backtest runs against the persisted history.
 */
@Testcontainers
class CryptoBacktestIT {

    @Container
    static final GenericContainer<?> DB = new GenericContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg17"))
            .withEnv("POSTGRES_DB", "traderbro")
            .withEnv("POSTGRES_USER", "traderbro")
            .withEnv("POSTGRES_PASSWORD", "traderbro")
            .withExposedPorts(5432);

    private JdbcTemplate jdbcTemplate() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432)
                        + "/traderbro", "traderbro", "traderbro");
        return new JdbcTemplate(ds);
    }

    @Test
    void cryptoHistoryRoundTripsAsFinalAndBacktests() throws Exception {
        JdbcTemplate jdbc = jdbcTemplate();
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase = new Liquibase(
                    "db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("migrations")), database);
            liquibase.update("");
        }

        BarRepository repo = new BarRepository(jdbc, 4);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double price = 100 + 10 * Math.sin(2 * Math.PI * i / 40.0) + i * 0.25;
            bars.add(Bar.builder().figi("BTCUSDT").interval(CandleInterval.ONE_HOUR)
                    .ts(start.plusSeconds(i * 3600L))
                    .open(bd(price)).high(bd(price + 1)).low(bd(price - 1))
                    .close(bd(price)).volume(100L).final_(true).build());
        }
        repo.upsert(bars);

        List<Bar> loaded = repo.findByFigiAndInterval("BTCUSDT", CandleInterval.ONE_HOUR,
                start, start.plusSeconds(300 * 3600L));
        assertThat(loaded).hasSize(300);
        // B1: closed candles must round-trip as final — indicator/backtest consumers rely on it.
        assertThat(loaded).allSatisfy(b -> assertThat(b.isFinal_()).isTrue());

        BarSeries series = SeriesFactory.fromDomain(loaded, DecimalNum::valueOf,
                loaded.size() + 10, "BTCUSDT");
        EmaMomentumStrategy strategy = new EmaMomentumStrategy(4);
        BacktestRunner runner = new BacktestRunner(4);
        BacktestRunner.BacktestResult result = runner.runDetailed(strategy, series,
                cryptoParams(), btc(), new BigDecimal("0.001"), new BigDecimal("5"),
                new BigDecimal("100000"), new BigDecimal("0.95"));
        assertThat(result.trades()).isNotEmpty();
        assertThat(result.metrics().numTrades()).isGreaterThan(0);
    }

    private static Instrument btc() {
        CryptoSpec spec = CryptoSpec.builder()
                .figi("BTCUSDT").ticker("BTCUSDT")
                .baseAsset("BTC").quoteAsset("USDT")
                .tickSize(new BigDecimal("0.01"))
                .stepSize(new BigDecimal("0.001"))
                .minNotional(new BigDecimal("5"))
                .build();
        return Instrument.builder()
                .figi("BTCUSDT").ticker("BTCUSDT")
                .name("BTC/USDT").currency("USDT").lot(1)
                .minPriceIncrement(new BigDecimal("0.01"))
                .board("BINANCE").tradable(true)
                .instrumentType(InstrumentType.CRYPTO)
                .cryptoSpec(spec)
                .build();
    }

    private static StrategyParams cryptoParams() {
        Map<String, String> m = new HashMap<>();
        m.put("fastEm", "12");
        m.put("slowEm", "26");
        m.put("rsiPeriod", "14");
        m.put("rsiMin", "30");
        m.put("rsiMax", "70");
        m.put("atrPeriod", "14");
        m.put("atrMult", "2");
        return new StrategyParams(m);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}