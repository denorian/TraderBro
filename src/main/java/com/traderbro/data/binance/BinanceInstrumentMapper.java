package com.traderbro.data.binance;

import com.traderbro.core.domain.CryptoSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.InstrumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/**
 * Maps a Binance {@code exchangeInfo} symbol entry into a {@code CRYPTO} {@link Instrument}.
 * Extracts tick/step/min-notional from the symbol's filter list and the listing date from
 * {@code onboardDate} (ms epoch, when present).
 */
public final class BinanceInstrumentMapper {

    private static final LocalDate EPOCH = Instant.EPOCH.atZone(ZoneOffset.UTC).toLocalDate();

    public Instrument toInstrument(JsonNode symbol) {
        String figi = symbol.path("symbol").asText();
        String base = symbol.path("baseAsset").asText();
        String quote = symbol.path("quoteAsset").asText();
        BigDecimal tick = null;
        BigDecimal step = null;
        BigDecimal minNotional = null;
        for (JsonNode f : symbol.path("filters")) {
            switch (f.path("filterType").asText()) {
                case "PRICE_FILTER" -> tick = dec(f.path("tickSize"));
                case "LOT_SIZE" -> step = dec(f.path("stepSize"));
                case "MIN_NOTIONAL" -> minNotional = dec(f.path("minNotional"));
                default -> { /* ignore other filters */ }
            }
        }
        boolean trading = "TRADING".equals(symbol.path("status").asText());
        boolean spotAllowed = symbol.path("isSpotTradingAllowed").asBoolean(true);
        LocalDate onboard = symbol.has("onboardDate") && symbol.path("onboardDate").asLong() > 0
                ? Instant.ofEpochMilli(symbol.path("onboardDate").asLong())
                        .atZone(ZoneOffset.UTC).toLocalDate()
                : EPOCH;

        CryptoSpec spec = CryptoSpec.builder()
                .figi(figi)
                .ticker(figi)
                .baseAsset(base)
                .quoteAsset(quote)
                .tickSize(tick)
                .stepSize(step)
                .minNotional(minNotional)
                .onboardDate(onboard)
                .build();

        return Instrument.builder()
                .figi(figi)
                .ticker(figi)
                .name(base + "/" + quote)
                .currency(quote)
                .lot(1)
                .minPriceIncrement(tick == null ? BigDecimal.ONE : tick)
                .board("BINANCE")
                .tradable(trading && spotAllowed)
                .instrumentType(InstrumentType.CRYPTO)
                .cryptoSpec(spec)
                .build();
    }

    private static BigDecimal dec(JsonNode n) {
        return n.isMissingNode() || n.asText().isBlank() ? null : new BigDecimal(n.asText());
    }
}