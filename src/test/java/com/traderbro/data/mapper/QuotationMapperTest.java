package com.traderbro.data.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;

class QuotationMapperTest {

    private static Quotation q(long units, int nano) {
        return Quotation.newBuilder().setUnits(units).setNano(nano).build();
    }

    private static MoneyValue m(long units, int nano) {
        return MoneyValue.newBuilder().setUnits(units).setNano(nano).build();
    }

    @Test
    void convertsPositiveQuotation() {
        assertThat(QuotationMapper.toBigDecimal(q(1, 500_000_000), 4))
                .isEqualByComparingTo("1.5000");
    }

    @Test
    void convertsMaxNano() {
        assertThat(QuotationMapper.toBigDecimal(q(0, 999_999_999), 4))
                .isEqualByComparingTo("1.0000");
    }

    @Test
    void convertsNegativeValues() {
        assertThat(QuotationMapper.toBigDecimal(q(-1, -500_000_000), 4))
                .isEqualByComparingTo("-1.5000");
    }

    @Test
    void convertsZero() {
        assertThat(QuotationMapper.toBigDecimal(q(0, 0), 4)).isEqualByComparingTo("0.0000");
        assertThat(QuotationMapper.toBigDecimal(q(0, 0), 4).signum()).isZero();
    }

    @Test
    void convertsMoneyValue() {
        assertThat(QuotationMapper.toBigDecimal(m(10, 250_000_000), 2))
                .isEqualByComparingTo("10.25");
    }
}