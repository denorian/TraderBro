package com.traderbro.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.domain.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class FuturesExpiryLockRuleTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final RiskConfig config = new RiskConfig(new BigDecimal("0.02"), new BigDecimal("0.10"),
            new BigDecimal("0.50"), 10, LocalTime.of(10, 0), LocalTime.of(18, 40),
            false, Duration.ofSeconds(60), MSK, 4, java.util.List.of(), new BigDecimal("0.30"), 2);

    private final FuturesExpiryLockRule rule = new FuturesExpiryLockRule(config);

    private Instrument future(LocalDate expiry) {
        return Instrument.builder().figi("MIXZ6").ticker("MIXZ6").instrumentType(InstrumentType.FUTURE)
                .futureSpec(FutureSpec.builder().figi("MIXZ6").ticker("MIXZ6").basicAsset("IMOEX")
                        .expirationDate(expiry).minPriceIncrement(BigDecimal.ONE)
                        .minPriceIncrementAmount(BigDecimal.ONE).build())
                .lot(1).minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    }

    private RiskContext ctx(Instrument instrument, LocalDate today) {
        Portfolio p = Portfolio.builder().totalValue(new BigDecimal("1000000"))
                .dayPnL(BigDecimal.ZERO).at(Instant.now()).build();
        Instant now = today.atStartOfDay(MSK).plusHours(12).toInstant();
        return new RiskContext("MIXZ6", instrument, OrderSide.BUY, 1, new BigDecimal("3000"),
                p, BigDecimal.ZERO, BigDecimal.ZERO, 0, false, now, Duration.ZERO,
                InstrumentType.FUTURE, BigDecimal.ZERO);
    }

    @Test
    void allowsWhenFarFromExpiry() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        RiskDecision d = rule.check(ctx(future(today.plusDays(30)), today));
        assertThat(d.isAllowed()).isTrue();
    }

    @Test
    void deniesWithinExpiryLockWindow() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        RiskDecision d = rule.check(ctx(future(today.plusDays(1)), today));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("FuturesExpiryLock");
    }

    @Test
    void allowsSharesAlways() {
        Instrument share = Instrument.builder().figi("S").ticker("S").lot(10)
                .minPriceIncrement(BigDecimal.ONE).tradable(true).build();
        RiskDecision d = rule.check(ctx(share, LocalDate.of(2026, 9, 1)));
        assertThat(d.isAllowed()).isTrue();
    }
}