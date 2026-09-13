package com.traderbro.data.mapper;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.data.mapper.QuotationMapper;
import java.math.BigDecimal;
import java.time.Instant;
import ru.tinkoff.piapi.contract.v1.Candle;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;

/**
 * Maps broker SDK candle types to the domain {@link Bar}. Only this package is allowed to
 * reference SDK candle types, keeping {@code core} and {@code execution} SDK-free.
 */
public final class SdkBarMapper {

    private final int moneyScale;

    public SdkBarMapper(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    /** Maps a historical (closed) candle. */
    public Bar toBar(String figi, HistoricCandle c, CandleInterval interval) {
        return Bar.builder()
                .figi(figi)
                .ts(Instant.ofEpochSecond(c.getTime().getSeconds(), c.getTime().getNanos()))
                .interval(interval)
                .open(QuotationMapper.toBigDecimal(c.getOpen(), moneyScale))
                .high(QuotationMapper.toBigDecimal(c.getHigh(), moneyScale))
                .low(QuotationMapper.toBigDecimal(c.getLow(), moneyScale))
                .close(QuotationMapper.toBigDecimal(c.getClose(), moneyScale))
                .volume(c.getVolume())
                .final_(c.getIsComplete())
                .build();
    }

    /** Maps a stream candle (may be forming: {@code isFinal=false}). */
    public Bar toBar(Candle c, CandleInterval interval) {
        return Bar.builder()
                .figi(c.getFigi())
                .ts(Instant.ofEpochSecond(c.getTime().getSeconds(), c.getTime().getNanos()))
                .interval(interval)
                .open(QuotationMapper.toBigDecimal(c.getOpen(), moneyScale))
                .high(QuotationMapper.toBigDecimal(c.getHigh(), moneyScale))
                .low(QuotationMapper.toBigDecimal(c.getLow(), moneyScale))
                .close(QuotationMapper.toBigDecimal(c.getClose(), moneyScale))
                .volume(c.getVolume())
                .final_(c.getIsFinal())
                .build();
    }
}