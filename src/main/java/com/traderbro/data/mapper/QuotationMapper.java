package com.traderbro.data.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;

/**
 * Converts broker SDK money types ({@code units} + {@code nano}) into {@link BigDecimal}.
 * <p>Edge cases handled: negative values (both {@code units} and {@code nano} negative) and
 * nano at the maximum scale (999999999). See the matching unit tests.
 */
public final class QuotationMapper {

    public static final int NANO_SCALE = 9;

    private QuotationMapper() {
    }

    /** Converts a {@link Quotation} to {@link BigDecimal} at the given money scale. */
    public static BigDecimal toBigDecimal(Quotation q, int scale) {
        return normalize(units(q.getUnits()), q.getNano(), scale);
    }

    /** Converts a {@link MoneyValue} to {@link BigDecimal} at the given money scale. */
    public static BigDecimal toBigDecimal(MoneyValue m, int scale) {
        return normalize(m.getUnits(), m.getNano(), scale);
    }

    private static BigDecimal normalize(long units, int nano, int scale) {
        BigDecimal u = BigDecimal.valueOf(units);
        BigDecimal n = BigDecimal.valueOf(nano, NANO_SCALE);
        BigDecimal sum = u.add(n);
        // Avoid "-0.0000" results: normalize a zero sum to positive zero.
        if (sum.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return sum.setScale(scale, RoundingMode.HALF_UP);
    }
}