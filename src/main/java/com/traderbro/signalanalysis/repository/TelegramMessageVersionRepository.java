package com.traderbro.signalanalysis.repository;

import com.traderbro.signalanalysis.domain.TelegramMessageVersion;
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

/** Append-only persistence for {@code telegram_message_versions}. */
@Repository
public class TelegramMessageVersionRepository {

    private static final String INSERT = """
            INSERT INTO telegram_message_versions
                (id, message_id, version_no, change_type, source_edited_at, observed_at,
                 content_type, text_content, formatted_entities, media_metadata, forward_metadata,
                 content_hash, source_event_id, created_at)
            VALUES (:id, :messageId, :versionNo, :changeType, :sourceEditedAt, :observedAt,
                    :contentType, :textContent, :formattedEntities, :mediaMetadata, :forwardMetadata,
                    :contentHash, :sourceEventId, :createdAt)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public TelegramMessageVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void insert(TelegramMessageVersion v) {
        named.update(INSERT, new MapSqlParameterSource()
                .addValue("id", v.getId())
                .addValue("messageId", v.getMessageId())
                .addValue("versionNo", v.getVersionNo())
                .addValue("changeType", v.getChangeType().name())
                .addValue("sourceEditedAt", ts(v.getSourceEditedAt()))
                .addValue("observedAt", ts(v.getObservedAt()))
                .addValue("contentType", v.getContentType())
                .addValue("textContent", v.getTextContent())
                .addValue("formattedEntities", v.getFormattedEntities())
                .addValue("mediaMetadata", v.getMediaMetadata())
                .addValue("forwardMetadata", v.getForwardMetadata())
                .addValue("contentHash", v.getContentHash())
                .addValue("sourceEventId", v.getSourceEventId())
                .addValue("createdAt", ts(v.getCreatedAt())));
    }

    /** Current highest version number for a message (0 when none exists). */
    public int latestVersionNo(UUID messageId) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM telegram_message_versions WHERE message_id = ?",
                Integer.class, messageId);
        return n == null ? 0 : n;
    }

    public List<TelegramMessageVersion> findByMessageId(UUID messageId) {
        return jdbc.query(
                "SELECT * FROM telegram_message_versions WHERE message_id = ? ORDER BY version_no",
                VERSION_MAPPER, messageId);
    }

    private static final RowMapper<TelegramMessageVersion> VERSION_MAPPER =
            new RowMapper<>() {
                @Override
                public TelegramMessageVersion mapRow(ResultSet rs, int i) throws SQLException {
                    return TelegramMessageVersion.builder()
                            .id(rs.getObject("id", UUID.class))
                            .messageId(rs.getObject("message_id", UUID.class))
                            .versionNo(rs.getInt("version_no"))
                            .changeType(com.traderbro.signalanalysis.domain.ChangeType
                                    .valueOf(rs.getString("change_type")))
                            .sourceEditedAt(rs.getTimestamp("source_edited_at") == null
                                    ? null : rs.getTimestamp("source_edited_at").toInstant())
                            .observedAt(rs.getTimestamp("observed_at").toInstant())
                            .contentType(rs.getString("content_type"))
                            .textContent(rs.getString("text_content"))
                            .formattedEntities(rs.getString("formatted_entities"))
                            .mediaMetadata(rs.getString("media_metadata"))
                            .forwardMetadata(rs.getString("forward_metadata"))
                            .contentHash(rs.getString("content_hash"))
                            .sourceEventId((UUID) rs.getObject("source_event_id"))
                            .createdAt(rs.getTimestamp("created_at").toInstant())
                            .build();
                }
            };

    private static Timestamp ts(java.time.Instant t) {
        return t == null ? null : Timestamp.from(t);
    }
}