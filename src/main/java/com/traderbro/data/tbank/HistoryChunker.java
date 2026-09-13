package com.traderbro.data.tbank;

import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.indicators.SeriesFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a history request range into chunks each holding at most {@code maxCandles},
 * because the broker caps the depth of a single candles request.
 * Pure and unit-testable.
 */
public final class HistoryChunker {

    private HistoryChunker() {
    }

    /** A half-open chunk [start, end). */
    public record Chunk(Instant start, Instant end) {
    }

    /**
     * @return contiguous chunks covering [from, to), each with at most maxCandles bars.
     */
    public static List<Chunk> chunk(Instant from, Instant to, CandleInterval interval, int maxCandles) {
        if (maxCandles <= 0) {
            throw new IllegalArgumentException("maxCandles must be positive");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        long intervalMillis = SeriesFactory.toDuration(interval).toMillis();
        long chunkMillis = intervalMillis * maxCandles;
        List<Chunk> chunks = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(to)) {
            Instant next = cursor.plusMillis(chunkMillis);
            if (next.isAfter(to)) {
                next = to;
            }
            chunks.add(new Chunk(cursor, next));
            cursor = next;
        }
        return chunks;
    }
}