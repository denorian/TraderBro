package com.traderbro.signalanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test (runs in verify via maven-failsafe): applies all Liquibase migrations on a
 * clean TimescaleDB container and checks the stage 4.0 signal-analysis schema — tables exist and
 * the natural-key unique constraints are enforced.
 */
@Testcontainers
class SignalAnalysisMigrationsIT {

    @Container
    static final GenericContainer<?> DB = new GenericContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg17"))
            .withEnv("POSTGRES_DB", "traderbro")
            .withEnv("POSTGRES_USER", "traderbro")
            .withEnv("POSTGRES_PASSWORD", "traderbro")
            .withExposedPorts(5432);

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432)
                        + "/traderbro", "traderbro", "traderbro");
        jdbc = new JdbcTemplate(ds);
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase = new Liquibase(
                    "db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("migrations")), database);
            liquibase.update("");
        }
    }

    @Test
    void signalAnalysisTablesExist() {
        for (String table : new String[]{"telegram_channels", "telegram_channel_sync_state",
                "telegram_messages", "telegram_message_versions", "telegram_ingest_events",
                "telegram_sync_runs"}) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(n).as("table %s exists", table).isEqualTo(1);
        }
    }

    @Test
    void channelMessageNaturalKeyIsUnique() {
        UUID channelId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO telegram_channels (id, telegram_chat_id, title, is_channel,
                    is_subscribed, is_archived, membership_status, first_seen_at, last_seen_at,
                    created_at, updated_at)
                VALUES (?, ?, 'Test', TRUE, TRUE, FALSE, 'MEMBER', ?, ?, ?, ?)
                """, channelId, 100L, ts(now), ts(now), ts(now), ts(now));

        jdbc.update("""
                INSERT INTO telegram_messages (id, channel_id, telegram_message_id, sent_at,
                    content_type, content_hash, current_version_no, availability_status,
                    first_observed_at, last_observed_at, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'TEXT', 'hash', 1, 'ACTIVE', ?, ?, ?, ?)
                """, UUID.randomUUID(), channelId, ts(now), ts(now), ts(now), ts(now), ts(now));

        // Second insert of the same (channel_id, telegram_message_id) must not create a duplicate.
        jdbc.update("""
                INSERT INTO telegram_messages (id, channel_id, telegram_message_id, sent_at,
                    content_type, content_hash, current_version_no, availability_status,
                    first_observed_at, last_observed_at, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'TEXT', 'hash2', 1, 'ACTIVE', ?, ?, ?, ?)
                ON CONFLICT (channel_id, telegram_message_id) DO NOTHING
                """, UUID.randomUUID(), channelId, ts(now), ts(now), ts(now), ts(now), ts(now));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM telegram_messages WHERE channel_id = ?", Integer.class,
                channelId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void inboxClaimPicksOnlyDueEvents() {
        Instant now = Instant.now();
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO telegram_channels (id, telegram_chat_id, title, is_channel,
                    is_subscribed, is_archived, membership_status, first_seen_at, last_seen_at,
                    created_at, updated_at)
                VALUES (?, 200, 'C', TRUE, TRUE, FALSE, 'MEMBER', ?, ?, ?, ?)
                """, channelId, ts(now), ts(now), ts(now), ts(now));
        jdbc.update("""
                INSERT INTO telegram_ingest_events (id, event_type, telegram_chat_id, payload,
                    received_at, status, attempts, available_at)
                VALUES (?, 'CHAT_METADATA', 200, '{}', ?, 'NEW', 0, ?)
                """, UUID.randomUUID(), ts(now), ts(now.minusSeconds(5)));
        jdbc.update("""
                INSERT INTO telegram_ingest_events (id, event_type, telegram_chat_id, payload,
                    received_at, status, attempts, available_at)
                VALUES (?, 'CHAT_METADATA', 200, '{}', ?, 'NEW', 0, ?)
                """, UUID.randomUUID(), ts(now), ts(now.plusSeconds(60))); // not due yet

        // Claim batch of 10 -> only the due event is returned.
        Long claimed = jdbc.queryForObject("""
                WITH batch AS (
                    SELECT received_seq FROM telegram_ingest_events
                    WHERE status IN ('NEW','RETRY') AND available_at <= ?
                    ORDER BY received_seq LIMIT 10 FOR UPDATE SKIP LOCKED
                )
                UPDATE telegram_ingest_events e
                SET status = 'PROCESSING', locked_at = ?, locked_by = 'test', attempts = attempts + 1
                FROM batch WHERE e.received_seq = batch.received_seq
                RETURNING e.received_seq
                """, Long.class, ts(now), ts(now));
        assertThat(claimed).isNotNull();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}