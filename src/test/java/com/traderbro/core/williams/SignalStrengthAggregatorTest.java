package com.traderbro.core.williams;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import com.traderbro.core.domain.enums.CandleInterval;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Signal-strength aggregation: repeated divergence/angulation signals from the same price block
 * accumulate into a combined strength, per the practitioner's "more arguments from one price
 * point → stronger signal" requirement.
 */
class SignalStrengthAggregatorTest {

    private final SignalStrengthAggregator agg = new SignalStrengthAggregator(
            new WilliamsProperties().getAggregation());

    private static DivergenceEvent div(DivergenceEvent.Direction dir, double price, double strength,
                                       int minutesAgo) {
        return DivergenceEvent.builder().id(UUID.randomUUID()).figi("BTCUSDT")
                .interval(CandleInterval.ONE_HOUR).ts(Instant.now().minusSeconds(minutesAgo * 60L))
                .direction(dir).degree(1).aoPeak1Ts(Instant.now()).aoPeak1Value(5)
                .aoPeak2Ts(Instant.now()).aoPeak2Value(3)
                .priceAtPeak1(price + 50).priceAtPeak2(price).strength(strength)
                .status(DivergenceEvent.Status.CONFIRMED).createdAt(Instant.now()).build();
    }

    private static AngulationEvent ang(UUID divId, double angleDeg) {
        return AngulationEvent.builder().figi("BTCUSDT").interval(CandleInterval.ONE_HOUR)
                .ts(Instant.now()).divergenceEventId(divId)
                .direction(AngulationEvent.Direction.AWAY_UP)
                .priceSlopeAtr(1).jawSlopeAtr(0).angleAtr(1).angleDeg(angleDeg)
                .anchorTs(Instant.now()).barsInWindow(4).createdAt(Instant.now()).build();
    }

    @Test
    void samePriceBlockAccumulatesStrengthAcrossTime() {
        DivergenceEvent d1 = div(DivergenceEvent.Direction.BEARISH, 100.0, 0.3, 1200); // 06:00
        DivergenceEvent d2 = div(DivergenceEvent.Direction.BEARISH, 100.8, 0.3, 300);  // 23:00 same block
        AngulationEvent a2 = ang(d2.getId(), 70.0); // strong angulation on the second
        // 1.0% block tolerance: 100 and 100.8 are within 0.8% → same block.
        List<SignalStrengthAggregator.BlockStrength> out =
                agg.aggregate(List.of(d1, d2), Map.of(d2.getId(), a2));

        assertThat(out).hasSize(1);
        SignalStrengthAggregator.BlockStrength b = out.get(0);
        assertThat(b.direction()).isEqualTo(DivergenceEvent.Direction.BEARISH);
        assertThat(b.signals()).isEqualTo(2);
        // 0.3 + 0.3 + (70/90 ≈ 0.778) ≈ 1.378
        assertThat(b.strength()).isGreaterThan(1.3);
        assertThat(b.strength()).isLessThan(1.4);
    }

    @Test
    void distantPriceBlocksAreSeparate() {
        DivergenceEvent d1 = div(DivergenceEvent.Direction.BEARISH, 100.0, 0.3, 600);
        DivergenceEvent d2 = div(DivergenceEvent.Direction.BEARISH, 150.0, 0.4, 100); // far away
        List<SignalStrengthAggregator.BlockStrength> out = agg.aggregate(List.of(d1, d2), Map.of());

        assertThat(out).hasSize(2);
        // Strongest first.
        assertThat(out.get(0).signals()).isEqualTo(1);
        assertThat(out.get(0).strength()).isEqualTo(0.4);
    }

    @Test
    void directionsAreSeparate() {
        DivergenceEvent bull = div(DivergenceEvent.Direction.BULLISH, 100.0, 0.3, 600);
        DivergenceEvent bear = div(DivergenceEvent.Direction.BEARISH, 100.1, 0.3, 600);
        List<SignalStrengthAggregator.BlockStrength> out = agg.aggregate(List.of(bull, bear), Map.of());

        assertThat(out).hasSize(2);
    }
}