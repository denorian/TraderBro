package com.traderbro.storage;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable audit log ({@code audit_events} table): every risk/order/kill-switch decision. */
@Repository
public class AuditEventRepository {

    private final NamedParameterJdbcTemplate named;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void insert(String level, String source, String message) {
        named.update("""
                INSERT INTO audit_events (ts, level, source, message)
                VALUES (:ts, :level, :source, :message)
                """, new MapSqlParameterSource()
                .addValue("ts", java.sql.Timestamp.from(Instant.now()))
                .addValue("level", level)
                .addValue("source", source)
                .addValue("message", message));
    }

    /** Convenience adapter so core components can emit audit events via a {@code Consumer<String>}. */
    public java.util.function.Consumer<String> sink(String source) {
        return message -> insert("INFO", source, message);
    }
}