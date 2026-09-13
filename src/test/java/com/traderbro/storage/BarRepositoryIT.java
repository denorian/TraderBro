package com.traderbro.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test: applies all Liquibase migrations on a clean TimescaleDB container and
 * verifies idempotent bar insertion (ON CONFLICT DO NOTHING).
 */
@Testcontainers
class BarRepositoryIT {

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
    void migrationsApplyAndBarsUpsertIdempotently() throws Exception {
        JdbcTemplate jdbc = jdbcTemplate();
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database);
            liquibase.update("");
        }
        // Bars table exists after migrations.
        Integer count = jdbc.queryForObject("SELECT count(*) FROM bars", Integer.class);
        assertThat(count).isZero();

        BarRepository repo = new BarRepository(jdbc, 4);
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        Bar bar = Bar.builder().figi("FIGI1").ts(ts).interval(CandleInterval.ONE_DAY)
                .open(new BigDecimal("100")).high(new BigDecimal("110"))
                .low(new BigDecimal("99")).close(new BigDecimal("105"))
                .volume(1000).final_(true).build();

        // Insert twice -> second pass is a no-op (idempotent).
        repo.upsert(List.of(bar));
        repo.upsert(List.of(bar));

        assertThat(repo.findByFigiAndInterval("FIGI1", CandleInterval.ONE_DAY,
                ts.minusSeconds(1), ts.plusSeconds(1))).hasSize(1);
        assertThat(repo.exists("FIGI1", CandleInterval.ONE_DAY, ts)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bars", Integer.class)).isEqualTo(1);
    }
}