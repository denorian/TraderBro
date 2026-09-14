package com.traderbro.core.futures;

import static org.assertj.core.api.Assertions.assertThat;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.InstrumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesContractSelectorTest {

    private Instrument future(String ticker, String asset, LocalDate expiry) {
        return Instrument.builder().figi(ticker).ticker(ticker).instrumentType(InstrumentType.FUTURE)
                .futureSpec(FutureSpec.builder().figi(ticker).ticker(ticker).basicAsset(asset)
                        .expirationDate(expiry).minPriceIncrement(BigDecimal.ONE)
                        .minPriceIncrementAmount(BigDecimal.ONE).build())
                .lot(1).minPriceIncrement(BigDecimal.ONE).tradable(true).build();
    }

    @Test
    void selectsNearestEligiblePerAsset() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        List<Instrument> all = List.of(
                future("MIX6", "IMOEX", today.plusDays(20)),
                future("MIX9", "IMOEX", today.plusDays(110)),
                future("SIZ6", "Si", today.plusDays(15)));
        // min-days-to-expiry 7 -> both eligible; nearest per asset chosen.
        List<Instrument> sel = FuturesContractSelector.selectNearest(all, List.of("IMOEX", "Si"), today, 7);
        assertThat(sel).extracting(Instrument::getTicker).containsExactly("MIX6", "SIZ6");
    }

    @Test
    void skipsContractsTooCloseToExpiry() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        List<Instrument> all = List.of(
                future("MIX6", "IMOEX", today.plusDays(3)),  // too close
                future("MIX9", "IMOEX", today.plusDays(100)));
        List<Instrument> sel = FuturesContractSelector.selectNearest(all, List.of("IMOEX"), today, 7);
        assertThat(sel).extracting(Instrument::getTicker).containsExactly("MIX9");
    }

    @Test
    void nearestAfterReturnsNextContractForRollover() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        List<Instrument> all = List.of(
                future("MIX6", "IMOEX", today.plusDays(10)),
                future("MIX9", "IMOEX", today.plusDays(110)));
        var next = FuturesContractSelector.nearestAfter(all, "IMOEX", today.plusDays(10));
        assertThat(next).isPresent();
        assertThat(next.get().getTicker()).isEqualTo("MIX9");
    }
}