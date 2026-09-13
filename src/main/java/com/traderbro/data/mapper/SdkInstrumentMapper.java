package com.traderbro.data.mapper;

import com.traderbro.core.domain.Instrument;
import java.math.BigDecimal;
import ru.tinkoff.piapi.contract.v1.Share;

/**
 * Maps SDK {@link Share} reference data to the domain {@link Instrument}.
 * Only this package references SDK reference types.
 */
public final class SdkInstrumentMapper {

    private final int moneyScale;

    public SdkInstrumentMapper(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    public Instrument toInstrument(Share s) {
        BigDecimal minStep = s.getMinPriceIncrement().getUnits() != 0 || s.getMinPriceIncrement().getNano() != 0
                ? QuotationMapper.toBigDecimal(s.getMinPriceIncrement(), moneyScale)
                : BigDecimal.ONE.setScale(moneyScale);
        return Instrument.builder()
                .figi(s.getFigi())
                .ticker(s.getTicker())
                .name(s.getName())
                .isin(s.getIsin())
                .currency(s.getCurrency())
                .lot(s.getLot())
                .minPriceIncrement(minStep)
                .board(s.getExchange() + "/" + s.getRealExchange()) // board is approximated; see TODO
                .tradable(s.getTradingStatus().getNumber() != 0 && !s.getForiClassFlag() && !s.getBlockedTcaFlag())
                .build();
    }
}