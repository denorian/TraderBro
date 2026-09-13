package com.traderbro.data.tbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryChunkerTest {

    private static final Instant FROM = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void splitsIntoChunksAtMostMaxCandles() {
        Instant to = FROM.plusSeconds(5 * 86400); // 5 days
        List<HistoryChunker.Chunk> chunks =
                HistoryChunker.chunk(FROM, to, CandleInterval.ONE_DAY, 2);
        assertThat(chunks).hasSize(3);
        for (HistoryChunker.Chunk c : chunks) {
            long days = java.time.Duration.between(c.start(), c.end()).toDays();
            assertThat(days).isLessThanOrEqualTo(2);
        }
        // chunks are contiguous and cover the range
        assertThat(chunks.get(0).start()).isEqualTo(FROM);
        assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(to);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).start()).isEqualTo(chunks.get(i - 1).end());
        }
    }

    @Test
    void singleChunkWhenRangeIsSmall() {
        Instant to = FROM.plusSeconds(86400);
        List<HistoryChunker.Chunk> chunks = HistoryChunker.chunk(FROM, to, CandleInterval.ONE_DAY, 10);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void rejectsInvalidInput() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HistoryChunker.chunk(FROM, FROM, CandleInterval.ONE_DAY, 2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HistoryChunker.chunk(FROM, FROM.plusSeconds(86400), CandleInterval.ONE_DAY, 0));
    }
}