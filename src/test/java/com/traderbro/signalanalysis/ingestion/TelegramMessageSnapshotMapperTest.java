package com.traderbro.signalanalysis.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The canonical SHA-256 hash must be stable for the same snapshot and differ on edits. */
class TelegramMessageSnapshotMapperTest {

    private final TelegramMessageSnapshotMapper mapper =
            new TelegramMessageSnapshotMapper(new ObjectMapper());

    private TelegramMessage message(String text, String entities, Instant editedAt) {
        return new TelegramMessage(5, 1, "USER", 1L, null, Instant.EPOCH, editedAt, null,
                new TelegramMessageContent("TEXT", text, entities, null, null));
    }

    @Test
    void hashIsStableForIdenticalSnapshot() {
        String h1 = mapper.contentHash(message("hello", null, null));
        String h2 = mapper.contentHash(message("hello", null, null));
        assertEquals(h1, h2);
        assertEquals(64, h1.length()); // SHA-256 hex
    }

    @Test
    void hashDiffersOnTextEdit() {
        assertNotEquals(mapper.contentHash(message("hello", null, null)),
                mapper.contentHash(message("bye", null, null)));
    }

    @Test
    void hashDiffersOnEditTimestamp() {
        assertNotEquals(mapper.contentHash(message("hello", null, null)),
                mapper.contentHash(message("hello", null, Instant.now())));
    }

    @Test
    void hashDiffersOnEntities() {
        String with = "[{\"offset\":0,\"length\":5,\"type\":\"bold\"}]";
        assertNotEquals(mapper.contentHash(message("hello", null, null)),
                mapper.contentHash(message("hello", with, null)));
    }
}