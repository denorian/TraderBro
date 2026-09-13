package com.traderbro.core.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionSizerTest {

    private final PositionSizer sizer = new PositionSizer(4);

    private Portfolio portfolio(BigDecimal total) {
        return Portfolio.builder().totalValue(total).at(Instant.now()).build();
    }

    private Instrument instrument(int lot) {
        return Instrument.builder().figi("F").ticker("X").lot(lot)
                .minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    }

    @Test
    void roundsDownToLots() {
        Portfolio p = portfolio(new BigDecimal("1000000"));
        // 10% budget = 100_000; price=200, lot=10 -> notional/lot=2000 -> 50 lots
        var r = sizer.size(new BigDecimal("0.10"), instrument(10), new BigDecimal("200"), p);
        assertThat(r.getLots()).isEqualTo(50);
        assertThat(r.getNotional()).isEqualByComparingTo("100000.0000");
    }

    @Test
    void returnsZeroWhenPriceHighForBudget() {
        Portfolio p = portfolio(new BigDecimal("1000000"));
        var r = sizer.size(new BigDecimal("0.10"), instrument(10), new BigDecimal("1000000"), p);
        assertThat(r.getLots()).isZero();
    }

    @Test
    void rejectsNonPositiveLot() {
        Portfolio p = portfolio(new BigDecimal("1000000"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> sizer.size(new BigDecimal("0.10"), instrument(0), new BigDecimal("200"), p));
    }

    @Test
    void effectivePctReflectsLotRounding() {
        Portfolio p = portfolio(new BigDecimal("100000"));
        var r = sizer.size(new BigDecimal("0.10"), instrument(1), new BigDecimal("10"), p);
        // budget 10k, notional/lot=10 -> 1000 lots, notional=10_000 -> 10%
        assertThat(r.getLots()).isEqualTo(1000);
        assertThat(r.getEffectivePct()).isEqualByComparingTo("0.100000");
    }
}