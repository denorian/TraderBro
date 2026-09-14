package com.traderbro.data.mapper;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.InstrumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.Share;

/**
 * Maps SDK reference types ({@link Share}, {@link Future}) to the domain {@link Instrument}.
 * Only this package references SDK reference types.
 */
public final class SdkInstrumentMapper {

    private final int moneyScale;

    public SdkInstrumentMapper(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    public Instrument toInstrument(Share s) {
        BigDecimal minStep = price(s.getMinPriceIncrement());
        return Instrument.builder()
                .figi(s.getFigi())
                .ticker(s.getTicker())
                .name(s.getName())
                .isin(s.getIsin())
                .currency(s.getCurrency())
                .lot(s.getLot())
                .minPriceIncrement(minStep)
                .board(s.getExchange() + "/" + s.getRealExchange())
                .tradable(s.getTradingStatus().getNumber() != 0 && !s.getForiClassFlag() && !s.getBlockedTcaFlag())
                .instrumentType(InstrumentType.SHARE)
                .build();
    }

    /** Maps an SDK {@link Future} to a domain instrument with a {@link FutureSpec}. */
    public Instrument toInstrument(Future f) {
        BigDecimal tick = price(f.getMinPriceIncrement());
        BigDecimal tickAmount = f.getMinPriceIncrementAmount().getUnits() != 0
                || f.getMinPriceIncrementAmount().getNano() != 0
                ? QuotationMapper.toBigDecimal(f.getMinPriceIncrementAmount(), moneyScale)
                : BigDecimal.ZERO.setScale(moneyScale);
        FutureSpec spec = FutureSpec.builder()
                .figi(f.getFigi())
                .ticker(f.getTicker())
                .basicAsset(f.getBasicAsset() == null || f.getBasicAsset().isBlank()
                        ? f.getTicker() : f.getBasicAsset())
                .lot(f.getLot())
                .minPriceIncrement(tick)
                .minPriceIncrementAmount(tickAmount)
                .expirationDate(toDate(f.getExpirationDate()))
                .firstTradeDate(toDate(f.getFirstTradeDate()))
                .initialMargin(f.getInitialMarginOnBuy() != null && (f.getInitialMarginOnBuy().getUnits() != 0
                        || f.getInitialMarginOnBuy().getNano() != 0)
                        ? QuotationMapper.toBigDecimal(f.getInitialMarginOnBuy(), moneyScale)
                        : BigDecimal.ZERO.setScale(moneyScale))
                .build();
        return Instrument.builder()
                .figi(f.getFigi())
                .ticker(f.getTicker())
                .name(f.getName())
                .isin(f.getIsin())
                .currency(f.getCurrency())
                .lot(f.getLot())
                .minPriceIncrement(tick)
                .board(f.getExchange())
                .tradable(f.getTradingStatus().getNumber() != 0 && !f.getForiClassFlag() && !f.getBlockedTcaFlag())
                .instrumentType(InstrumentType.FUTURE)
                .futureSpec(spec)
                .build();
    }

    private BigDecimal price(ru.tinkoff.piapi.contract.v1.Quotation q) {
        return (q.getUnits() != 0 || q.getNano() != 0)
                ? QuotationMapper.toBigDecimal(q, moneyScale)
                : BigDecimal.ONE.setScale(moneyScale);
    }

    private static LocalDate toDate(ru.tinkoff.piapi.contract.v1.Timestamp ts) {
        if (ts == null) {
            return null;
        }
        // TODO: verify API — Timestamp field names in the SDK contract.
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos())
                .atZone(ZoneId.of("Europe/Moscow")).toLocalDate();
    }
}