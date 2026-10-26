package com.traderbro.data.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.CryptoSpec;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Binance mapping: klines (REST array + WebSocket object) → {@link Bar}, {@code exchangeInfo}
 * symbol → {@link Instrument}, interval mapping, and the application-level PING/PONG keep-alive.
 */
class BinanceMarketDataProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsRestKlineToBar() {
        ArrayNode row = JSON.createArrayNode();
        row.add(1_499_040_000_000L);       // open time (ms, day boundary)
        row.add("0.01634790");             // open
        row.add("0.80000000");             // high
        row.add("0.01575800");             // low
        row.add("0.01577100");             // close
        row.add("0");                      // volume (zero-volume edge case)

        Bar bar = BinanceMarketDataProvider.barFromRestRow(row, "BTCUSDT", CandleInterval.ONE_DAY);
        assertThat(bar).isNotNull();
        assertThat(bar.getFigi()).isEqualTo("BTCUSDT");
        assertThat(bar.getTs()).isEqualTo(Instant.ofEpochMilli(1_499_040_000_000L));
        assertThat(bar.getInterval()).isEqualTo(CandleInterval.ONE_DAY);
        assertThat(bar.getClose()).isEqualByComparingTo("0.01577100");
        assertThat(bar.getVolume()).isZero();
        assertThat(bar.isFinal_()).isTrue();
    }

    @Test
    void rejectsMalformedKlineRow() {
        ArrayNode row = JSON.createArrayNode();
        row.add(1L); // too short
        assertThat(BinanceMarketDataProvider.barFromRestRow(row, "BTCUSDT", CandleInterval.ONE_DAY))
                .isNull();
    }

    @Test
    void mapsIntervalToStringAndBack() {
        assertThat(BinanceMarketDataProvider.intervalToBinance(CandleInterval.FIFTEEN_MIN))
                .isEqualTo("15m");
        assertThat(BinanceMarketDataProvider.intervalToBinance(CandleInterval.ONE_HOUR))
                .isEqualTo("1h");
        assertThat(BinanceMarketDataProvider.intervalToBinance(CandleInterval.ONE_DAY))
                .isEqualTo("1d");
        assertThat(BinanceMarketDataProvider.intervalFromBinance("1h"))
                .isEqualTo(CandleInterval.ONE_HOUR);
        assertThatThrownBy(() -> BinanceMarketDataProvider.intervalFromBinance("xyz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsExchangeInfoSymbolToCryptoInstrument() {
        ObjectNode symbol = JSON.createObjectNode();
        symbol.put("symbol", "BTCUSDT");
        symbol.put("status", "TRADING");
        symbol.put("baseAsset", "BTC");
        symbol.put("quoteAsset", "USDT");
        symbol.put("isSpotTradingAllowed", true);
        symbol.put("onboardDate", 1410847200000L);
        ArrayNode filters = JSON.createArrayNode();
        filters.add(filter("PRICE_FILTER", "tickSize", "0.01000000"));
        filters.add(filter("LOT_SIZE", "stepSize", "0.00100000"));
        filters.add(filter("MIN_NOTIONAL", "minNotional", "5.00000000"));
        symbol.set("filters", filters);

        Instrument inst = new BinanceInstrumentMapper().toInstrument(symbol);
        assertThat(inst.getInstrumentType()).isEqualTo(InstrumentType.CRYPTO);
        assertThat(inst.getTicker()).isEqualTo("BTCUSDT");
        assertThat(inst.getCurrency()).isEqualTo("USDT");
        assertThat(inst.isTradable()).isTrue();
        assertThat(inst.getLot()).isEqualTo(1);
        CryptoSpec spec = inst.getCryptoSpec();
        assertThat(spec.getBaseAsset()).isEqualTo("BTC");
        assertThat(spec.getQuoteAsset()).isEqualTo("USDT");
        assertThat(spec.getTickSize()).isEqualByComparingTo("0.01");
        assertThat(spec.getStepSize()).isEqualByComparingTo("0.001");
        assertThat(spec.getMinNotional()).isEqualByComparingTo("5");
        assertThat(spec.getOnboardDate()).isNotNull();
        spec.assertValid();
    }

    @Test
    void nonTradingOrBlockedSpotSymbolIsNotTradable() {
        ObjectNode symbol = JSON.createObjectNode();
        symbol.put("symbol", "XRPUSDT");
        symbol.put("status", "TRADING");
        symbol.put("baseAsset", "XRP");
        symbol.put("quoteAsset", "USDT");
        symbol.put("isSpotTradingAllowed", false);
        symbol.set("filters", JSON.createArrayNode());

        Instrument inst = new BinanceInstrumentMapper().toInstrument(symbol);
        assertThat(inst.isTradable()).isFalse();
    }

    @Test
    void invalidSpecFailsValidation() {
        ObjectNode symbol = JSON.createObjectNode();
        symbol.put("symbol", "BTCUSDT");
        symbol.put("status", "TRADING");
        symbol.put("baseAsset", "BTC");
        symbol.put("quoteAsset", "USDT");
        symbol.put("isSpotTradingAllowed", true);
        ArrayNode filters = JSON.createArrayNode();
        filters.add(filter("LOT_SIZE", "stepSize", "0.00100000"));
        symbol.set("filters", filters); // no PRICE_FILTER -> tickSize null

        CryptoSpec spec = new BinanceInstrumentMapper().toInstrument(symbol).getCryptoSpec();
        assertThatThrownBy(spec::assertValid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wsKlineEventProducesBarWithFinalFlag() {
        ObjectNode k = JSON.createObjectNode();
        k.put("t", 1_499_040_000_000L);
        k.put("o", "0.01634790");
        k.put("h", "0.80000000");
        k.put("l", "0.01575800");
        k.put("c", "0.01577100");
        k.put("v", "1000");
        k.put("x", true);
        ObjectNode frame = JSON.createObjectNode();
        frame.put("e", "kline");
        frame.put("s", "BTCUSDT");
        frame.set("k", k);

        AtomicReference<Bar> captured = new AtomicReference<>();
        BinanceMarketDataProvider.handleText(stubWebSocket(), frame.toString(),
                captured::set, "BTCUSDT", CandleInterval.ONE_HOUR);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().isFinal_()).isTrue();
        assertThat(captured.get().getClose()).isEqualByComparingTo("0.01577100");
    }

    @Test
    void combinedStreamKlineUsesEventSymbolNotPositionalFigi() {
        // Combined streams wrap the payload under "data" and can carry any subscribed pair.
        ObjectNode k = JSON.createObjectNode();
        k.put("t", 1_499_040_000_000L);
        k.put("o", "500");
        k.put("h", "510");
        k.put("l", "490");
        k.put("c", "505");
        k.put("v", "90778.71500000"); // fractional string volume
        k.put("x", true);
        ObjectNode data = JSON.createObjectNode();
        data.put("e", "kline");
        data.put("s", "BNBUSDT");
        data.set("k", k);
        ObjectNode frame = JSON.createObjectNode();
        frame.put("stream", "bnbusdt@kline_1h");
        frame.set("data", data);

        AtomicReference<Bar> captured = new AtomicReference<>();
        BinanceMarketDataProvider.handleText(stubWebSocket(), frame.toString(),
                captured::set, "BTCUSDT", CandleInterval.ONE_HOUR);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getFigi()).isEqualTo("BNBUSDT"); // from event, not BTCUSDT
        assertThat(captured.get().getVolume()).isEqualTo(90_778L);
    }

    @Test
    void wsPingIsAnsweredWithPong() {
        AtomicReference<String> sent = new AtomicReference<>();
        BinanceMarketDataProvider.handleText(wsCapturing(sent::set), "{\"ping\":123456789}",
                ignored -> { }, "BTCUSDT", CandleInterval.ONE_HOUR);
        assertThat(sent.get()).isEqualTo("{\"pong\":123456789}");
    }

    private static ObjectNode filter(String type, String key, String value) {
        ObjectNode f = JSON.createObjectNode();
        f.put("filterType", type);
        f.put(key, value);
        return f;
    }

    private static WebSocket stubWebSocket() {
        return new WebSocket() {
            @Override
            public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
                return null;
            }

            @Override
            public void request(long n) {
            }

            @Override
            public String getSubprotocol() {
                return "";
            }

            @Override
            public boolean isOutputClosed() {
                return false;
            }

            @Override
            public boolean isInputClosed() {
                return false;
            }

            @Override
            public void abort() {
            }
        };
    }

    private static WebSocket wsCapturing(Consumer<String> onText) {
        return new WebSocket() {
            @Override
            public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
                onText.accept(data.toString());
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
                return null;
            }

            @Override
            public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
                return null;
            }

            @Override
            public void request(long n) {
            }

            @Override
            public String getSubprotocol() {
                return "";
            }

            @Override
            public boolean isOutputClosed() {
                return false;
            }

            @Override
            public boolean isInputClosed() {
                return false;
            }

            @Override
            public void abort() {
            }
        };
    }
}