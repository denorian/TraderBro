package com.traderbro.data.mapper;

import com.google.protobuf.Timestamp;
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
                .tradable(!s.getBlockedTcaFlag())
                .instrumentType(InstrumentType.SHARE)
                .build();
    }

    /** Maps an SDK {@link Future} to a domain instrument with a {@link FutureSpec}. */
    public Instrument toInstrument(Future f) {
        BigDecimal tick = price(f.getMinPriceIncrement());
        BigDecimal tickAmount = hasValue(f.getMinPriceIncrementAmount())
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
                .initialMargin(hasValue(f.getInitialMarginOnBuy())
                        ? QuotationMapper.toBigDecimal(f.getInitialMarginOnBuy(), moneyScale)
                        : BigDecimal.ZERO.setScale(moneyScale))
                .build();
        return Instrument.builder()
                .figi(f.getFigi())
                .ticker(f.getTicker())
                .name(f.getName())
                .currency(f.getCurrency())
                .lot(f.getLot())
                .minPriceIncrement(tick)
                .board(f.getExchange())
                .tradable(!f.getBlockedTcaFlag())
                .instrumentType(InstrumentType.FUTURE)
                .futureSpec(spec)
                .build();
    }

    private BigDecimal price(ru.tinkoff.piapi.contract.v1.Quotation q) {
        return hasValue(q)
                ? QuotationMapper.toBigDecimal(q, moneyScale)
                : BigDecimal.ONE.setScale(moneyScale);
    }

    private static boolean hasValue(ru.tinkoff.piapi.contract.v1.Quotation q) {
        return q != null && (q.getUnits() != 0 || q.getNano() != 0);
    }

    private static boolean hasValue(ru.tinkoff.piapi.contract.v1.MoneyValue m) {
        return m != null && (m.getUnits() != 0 || m.getNano() != 0);
    }

    private static LocalDate toDate(Timestamp ts) {
        if (ts == null || (ts.getSeconds() == 0 && ts.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos())
                .atZone(ZoneId.of("Europe/Moscow")).toLocalDate();
    }
}