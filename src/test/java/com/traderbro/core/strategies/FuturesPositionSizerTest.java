package com.traderbro.core.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.enums.InstrumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FuturesPositionSizerTest {

    private final FuturesPositionSizer sizer = new FuturesPositionSizer(4);

    private Instrument future() {
        FutureSpec spec = FutureSpec.builder().figi("MIXZ6").ticker("MIXZ6").basicAsset("IMOEX")
                .lot(1).minPriceIncrement(BigDecimal.ONE).minPriceIncrementAmount(new BigDecimal("100"))
                .expirationDate(LocalDate.now().plusMonths(2)).initialMargin(new BigDecimal("10000")).build();
        return Instrument.builder().figi("MIXZ6").ticker("MIXZ6").instrumentType(InstrumentType.FUTURE)
                .futureSpec(spec).lot(1).minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    }

    private Portfolio portfolio() {
        return Portfolio.builder().totalValue(new BigDecimal("1000000")).at(Instant.now()).build();
    }

    @Test
    void pointValueIsTickAmountOverTick() {
        assertThat(future().getFutureSpec().pointValue()).isEqualByComparingTo("100");
    }

    @Test
    void sizesByRiskCapitalAndStopDistance() {
        // risk capital 1% = 10 000; stop 3000->2980 = 20 pts; risk/contract = 20*100=2000 -> 5 contracts
        var r = sizer.size(new BigDecimal("0.01"), future(), new BigDecimal("3000"),
                new BigDecimal("2980"), BigDecimal.ZERO, new BigDecimal("0.30"), portfolio());
        assertThat(r.getContracts()).isEqualTo(5);
        assertThat(r.getNewMargin()).isEqualByComparingTo("50000.0000");
        assertThat(r.isMarginLimitHit()).isFalse();
    }

    @Test
    void capsContractsByMargin() {
        // current GO 290k of 300k budget -> only 1 contract allowed
        var r = sizer.size(new BigDecimal("0.01"), future(), new BigDecimal("3000"),
                new BigDecimal("2980"), new BigDecimal("290000"), new BigDecimal("0.30"), portfolio());
        assertThat(r.getContracts()).isEqualTo(1);
    }

    @Test
    void rejectsOnMarginLimit() {
        // margin budget fully consumed -> zero contracts, marginLimitHit
        var r = sizer.size(new BigDecimal("0.01"), future(), new BigDecimal("3000"),
                new BigDecimal("2980"), new BigDecimal("300000"), new BigDecimal("0.30"), portfolio());
        assertThat(r.getContracts()).isZero();
        assertThat(r.isMarginLimitHit()).isTrue();
    }

    @Test
    void rejectsNonFuture() {
        Instrument share = Instrument.builder().figi("S").ticker("S").lot(10)
                .minPriceIncrement(BigDecimal.ONE).tradable(true).build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> sizer.size(new BigDecimal("0.01"), share, new BigDecimal("100"),
                        new BigDecimal("99"), BigDecimal.ZERO, new BigDecimal("0.30"), portfolio()));
    }
}