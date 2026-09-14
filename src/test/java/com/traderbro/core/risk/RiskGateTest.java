package com.traderbro.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskGateTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private RiskGate gate;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        RiskConfig rc = new RiskConfig(new BigDecimal("0.02"), new BigDecimal("0.10"),
                new BigDecimal("0.50"), 10, LocalTime.of(10, 0), LocalTime.of(18, 40),
                false, Duration.ofSeconds(60), MSK, 4,
                java.util.List.of(new RiskConfig.TradingWindow(LocalTime.of(10, 0), LocalTime.of(14, 0)),
                        new RiskConfig.TradingWindow(LocalTime.of(19, 0), LocalTime.of(23, 50))),
                new BigDecimal("0.30"), 2);
        List<RiskRule> rules = List.of(new KillSwitchRule(), new DailyLossLimitRule(rc),
                new PositionLimitRule(rc), new ExposureLimitRule(rc),
                new OrderRateLimitRule(rc), new FuturesExpiryLockRule(rc),
                new TradingWindowRule(rc), new DataFreshnessRule(rc));
        gate = new RiskGate(rules, msg -> { });
        instrument = Instrument.builder().figi("F").ticker("X").lot(10)
                .minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    }

    private static Instant moscow(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MSK).toInstant();
    }

    private static Portfolio okPortfolio() {
        return Portfolio.builder().totalValue(new BigDecimal("1000000"))
                .dayPnL(new BigDecimal("1000")).at(Instant.now()).build();
    }

    private RiskContext base() {
        return new RiskContext("F", instrument, OrderSide.BUY, 20, new BigDecimal("200"),
                okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5));
    }

    @Test
    void allowsWhenAllSafe() {
        assertThat(gate.evaluate(base()).isAllowed()).isTrue();
    }

    @Test
    void deniesOnKillSwitch() {
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 2, true,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("KillSwitch");
    }

    @Test
    void deniesOnDailyLossLimit() {
        Portfolio losing = Portfolio.builder().totalValue(new BigDecimal("1000000"))
                .dayPnL(new BigDecimal("-30000")).at(Instant.now()).build();
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), losing, BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("DailyLossLimit");
    }

    @Test
    void allowsAtExactDailyLossBoundary() {
        Portfolio losing = Portfolio.builder().totalValue(new BigDecimal("1000000"))
                .dayPnL(new BigDecimal("-20000")).at(Instant.now()).build();
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), losing, BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isTrue();
    }

    @Test
    void deniesOnPositionLimit() {
        // notional = 200*20*10 = 40_000; position 80_000 -> after 120_000 > 100_000 limit
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), new BigDecimal("80000"),
                BigDecimal.ZERO, 2, false, moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("PositionLimit");
    }

    @Test
    void deniesOnExposureLimit() {
        // notional 40_000 + exposure 480_000 = 520_000 > 500_000 limit
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO,
                new BigDecimal("480000"), 2, false, moscow(2026, 9, 14, 13, 0),
                Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("ExposureLimit");
    }

    @Test
    void deniesOnOrderRateLimit() {
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 10, false,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("OrderRateLimit");
    }

    @Test
    void deniesOutsideTradingWindow() {
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 14, 21, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("TradingWindow");
    }

    @Test
    void deniesOnWeekend() {
        // Saturday 2026-09-12
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 12, 13, 0), Duration.ofSeconds(5)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("TradingWindow");
    }

    @Test
    void deniesOnStaleStream() {
        RiskDecision d = gate.evaluate(new RiskContext("F", instrument, OrderSide.BUY, 20,
                new BigDecimal("200"), okPortfolio(), BigDecimal.ZERO, BigDecimal.ZERO, 2, false,
                moscow(2026, 9, 14, 13, 0), Duration.ofSeconds(120)));
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRule()).isEqualTo("DataFreshness");
    }
}