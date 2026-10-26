package com.traderbro.data.binance;

import com.traderbro.api.config.CryptoProperties;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.domain.spi.StreamSubscription;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link MarketDataProvider} backed by the Binance spot public API (no API key required for
 * market data). Provides historical klines via REST {@code /api/v3/klines} and real-time candles
 * via the combined WebSocket {@code /stream}. Crypto is read-only in this stage: this provider is
 * used for data, analytics and backtesting only; no trade execution.
 */
@Slf4j
public class BinanceMarketDataProvider implements MarketDataProvider {

    /** Binance klines weight for a limit=1000 request. */
    private static final int KLINE_WEIGHT = 2;
    private static final int MAX_WS_RECONNECTS = 5;
    private static final long EXCHANGE_CACHE_MS = 60 * 60 * 1000L;
    /** HTTP 418/429 status codes mean "you're being rate limited" — retry after a backoff. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_IP_BANNED = 418;

    private final CryptoProperties props;
    private final CryptoProperties.Binance binance;
    private static final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final BinanceRateLimiter rateLimiter;
    private final BinanceInstrumentMapper instrumentMapper = new BinanceInstrumentMapper();

    // Exchange-info cache (symbols are static); refreshed at most once an hour.
    private volatile List<Instrument> cachedInstruments = List.of();
    private volatile long cacheTimestampMs = 0;

    public BinanceMarketDataProvider(CryptoProperties props) {
        this.props = props;
        this.binance = props.getBinance();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(binance.getConnectTimeoutMs()))
                .build();
        this.rateLimiter = new BinanceRateLimiter(binance.getRateLimitWeightPerMin());
    }

    // ------------------------------------------------------------------ history (REST)

    @Override
    public List<Bar> getHistory(String figi, Instant from, Instant to, CandleInterval interval) {
        String bin = intervalToBinance(interval);
        List<Bar> all = new ArrayList<>();
        long startMs = from.toEpochMilli();
        long endMs = to.toEpochMilli();
        while (startMs < endMs) {
            List<Bar> chunk = fetchKlines(figi, bin, startMs, endMs);
            if (chunk.isEmpty()) {
                break;
            }
            all.addAll(chunk);
            long lastOpen = chunk.get(chunk.size() - 1).getTs().toEpochMilli();
            if (chunk.size() < 1000 || lastOpen >= endMs) {
                break;
            }
            startMs = lastOpen + 1;
        }
        return all;
    }

    private List<Bar> fetchKlines(String figi, String binanceInterval, long startMs, long endMs) {
        String url = binance.getBaseUrl() + "/api/v3/klines"
                + "?symbol=" + figi
                + "&interval=" + binanceInterval
                + "&startTime=" + startMs
                + "&endTime=" + endMs
                + "&limit=1000";
        JsonNode body = getJsonWithRetry(url, KLINE_WEIGHT);
        List<Bar> bars = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return bars;
        }
        for (JsonNode row : body) {
            Bar bar = barFromRestRow(row, figi, intervalFromBinance(binanceInterval));
            if (bar != null && !bar.getTs().isAfter(Instant.ofEpochMilli(endMs))) {
                bars.add(bar);
            }
        }
        return bars;
    }

    /** Maps one REST kline array entry into a {@link Bar}, or null for malformed rows. */
    static Bar barFromRestRow(JsonNode row, String figi, CandleInterval interval) {
        if (!row.isArray() || row.size() < 6) {
            return null;
        }
        return Bar.builder()
                .figi(figi)
                .ts(Instant.ofEpochMilli(row.get(0).asLong()))
                .interval(interval)
                .open(new BigDecimal(row.get(1).asText()))
                .high(new BigDecimal(row.get(2).asText()))
                .low(new BigDecimal(row.get(3).asText()))
                .close(new BigDecimal(row.get(4).asText()))
                .volume(volumeToLong(row.get(5)))
                .final_(true)
                .build();
    }

    /** Binance klines volume is a string (e.g. "204.37268000"); {@code Bar.volume} is a long. */
    static long volumeToLong(JsonNode v) {
        try {
            return new BigDecimal(v.asText()).longValue();
        } catch (Exception e) {
            return v.asLong(0);
        }
    }

    // ------------------------------------------------------------------ real-time stream (WebSocket)

    @Override
    public StreamSubscription subscribeCandles(List<String> figis, CandleInterval interval,
                                               Consumer<Bar> onBar) {
        String bin = intervalToBinance(interval);
        String streams = figis.stream()
                .map(f -> f.toLowerCase(java.util.Locale.ROOT) + "@kline_" + bin)
                .collect(Collectors.joining("/"));
        BinanceStreamSubscription sub = new BinanceStreamSubscription(figis, interval, onBar);
        connect(sub, streams, 0);
        return sub;
    }

    private void connect(BinanceStreamSubscription sub, String streams, int attempt) {
        if (sub.isCancelled()) {
            return;
        }
        try {
            String url = binance.getWsUrl() + "/stream?streams=" + streams;
            WebSocket ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(binance.getConnectTimeoutMs()))
                    .buildAsync(URI.create(url), sub.listener())
                    .join();
            sub.attach(ws);
            log.info("binance stream connected: {}", streams);
        } catch (RuntimeException e) {
            log.warn("binance stream connect failed (attempt {}): {}", attempt + 1, e.getMessage());
            reconnect(sub, streams, attempt);
        }
    }

    private void reconnect(BinanceStreamSubscription sub, String streams, int attempt) {
        if (sub.isCancelled()) {
            return;
        }
        if (attempt >= MAX_WS_RECONNECTS) {
            log.error("binance stream {} gave up after {} attempts", streams, MAX_WS_RECONNECTS);
            sub.markDegraded();
            return;
        }
        long backoff = 1000L * (1L << attempt);
        log.warn("binance stream {} reconnect in {} ms", streams, backoff);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        connect(sub, streams, attempt + 1);
    }

    /**
     * Handles a stream text frame. Supports both raw streams ({@code {"e":"kline","s":..,"k":{..}}}
     * at the root) and combined streams (the payload is nested under {@code "data"}). Replies to
     * Binance's application-level {@code {"ping":<ts>}} keep-alive with {@code {"pong":<ts>}},
     * and turns {@code kline} events into {@link Bar}s — keyed by the event's own symbol, not a
     * positional figi, since one combined connection carries every subscribed pair.
     */
    static void handleText(WebSocket ws, String text, Consumer<Bar> onBar, String fallbackFigi,
                           CandleInterval interval) {
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (Exception e) {
            return;
        }
        // Combined streams nest the payload under "data"; raw streams put it at the root.
        JsonNode d = root.has("data") ? root.path("data") : root;
        JsonNode ping = root.has("ping") ? root.path("ping") : d.path("ping");
        if (!ping.isMissingNode()) {
            long ts = ping.asLong();
            ws.sendText("{\"pong\":" + ts + "}", true);
            return;
        }
        JsonNode k = d.path("k");
        if (k.isMissingNode()) {
            return;
        }
        String figi = d.path("s").asText();
        if (figi.isBlank()) {
            figi = fallbackFigi;
        }
        long openTimeMs = k.path("t").asLong();
        onBar.accept(Bar.builder()
                .figi(figi)
                .ts(Instant.ofEpochMilli(openTimeMs))
                .interval(interval)
                .open(new BigDecimal(k.path("o").asText()))
                .high(new BigDecimal(k.path("h").asText()))
                .low(new BigDecimal(k.path("l").asText()))
                .close(new BigDecimal(k.path("c").asText()))
                .volume(volumeToLong(k.path("v")))
                .final_(k.path("x").asBoolean())
                .build());
    }

    // ------------------------------------------------------------------ instruments (REST)

    @Override
    public List<Instrument> findInstruments(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now - cacheTimestampMs < EXCHANGE_CACHE_MS && !cachedInstruments.isEmpty()) {
            return cachedInstruments;
        }
        String symbols = tickers.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        String url = binance.getBaseUrl() + "/api/v3/exchangeInfo?symbols=" + uriEncode(symbols);
        JsonNode body = getJsonWithRetry(url, 20);
        List<Instrument> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode sym : body.path("symbols")) {
                out.add(instrumentMapper.toInstrument(sym));
            }
        }
        if (!out.isEmpty()) {
            cachedInstruments = out;
            cacheTimestampMs = now;
        }
        return out;
    }

    @Override
    public List<Instrument> findAllFutures(List<String> basicAssets) {
        // Spot crypto only; Binance futures are out of scope for this stage.
        return List.of();
    }

    // ------------------------------------------------------------------ shared REST plumbing

    /**
     * GETs a URL under the weight budget, retrying with backoff on 429/418 (Binance rate limiting).
     * Returns the parsed JSON body, or null if the request ultimately failed.
     */
    private JsonNode getJsonWithRetry(String url, int weight) {
        for (int attempt = 0; attempt < 3; attempt++) {
            rateLimiter.acquire(weight);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(binance.getReadTimeoutMs()))
                    .GET()
                    .build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == HTTP_TOO_MANY_REQUESTS || code == HTTP_IP_BANNED) {
                    long backoff = 1000L * (attempt + 1);
                    log.warn("binance {} -> HTTP {}, backoff {} ms", url, code, backoff);
                    Thread.sleep(backoff);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    log.warn("binance {} -> HTTP {}: {}", url, code, resp.body());
                    return null;
                }
                return json.readTree(resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("binance GET {} failed: {}", url, e.getMessage());
            }
        }
        return null;
    }

    static String intervalToBinance(CandleInterval interval) {
        return switch (interval) {
            case ONE_MIN -> "1m";
            case FIVE_MIN -> "5m";
            case FIFTEEN_MIN -> "15m";
            case ONE_HOUR -> "1h";
            case FOUR_HOUR -> "4h";
            case ONE_DAY -> "1d";
        };
    }

    static CandleInterval intervalFromBinance(String binance) {
        return switch (binance) {
            case "1m" -> CandleInterval.ONE_MIN;
            case "5m" -> CandleInterval.FIVE_MIN;
            case "15m" -> CandleInterval.FIFTEEN_MIN;
            case "1h" -> CandleInterval.ONE_HOUR;
            case "4h" -> CandleInterval.FOUR_HOUR;
            case "1d" -> CandleInterval.ONE_DAY;
            default -> throw new IllegalArgumentException("unknown binance interval: " + binance);
        };
    }

    private static String uriEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Stream subscription handle. Uses a per-subscription {@link WebSocket.Listener} so the
     * provider can open multiple independent connections. On an unexpected close/error it
     * reconnects with exponential backoff (up to {@value MAX_WS_RECONNECTS} attempts).
     */
    private final class BinanceStreamSubscription implements StreamSubscription {
        private final List<String> figis;
        private final CandleInterval interval;
        private final Consumer<Bar> onBar;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean degraded = new AtomicBoolean(false);
        private final AtomicInteger activeAttempt = new AtomicInteger(0);
        private volatile WebSocket ws;

        BinanceStreamSubscription(List<String> figis, CandleInterval interval, Consumer<Bar> onBar) {
            this.figis = figis;
            this.interval = interval;
            this.onBar = onBar;
        }

        WebSocket.Listener listener() {
            return new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    handleText(webSocket, data.toString(), onBar, figis.get(0), interval);
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    if (!cancelled.get()) {
                        String streams = figis.stream()
                                .map(f -> f.toLowerCase(java.util.Locale.ROOT) + "@kline_"
                                        + intervalToBinance(interval))
                                .collect(Collectors.joining("/"));
                        reconnect(BinanceStreamSubscription.this, streams, activeAttempt.getAndIncrement());
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    if (!cancelled.get()) {
                        String streams = figis.stream()
                                .map(f -> f.toLowerCase(java.util.Locale.ROOT) + "@kline_"
                                        + intervalToBinance(interval))
                                .collect(Collectors.joining("/"));
                        reconnect(BinanceStreamSubscription.this, streams, activeAttempt.getAndIncrement());
                    }
                }
            };
        }

        void attach(WebSocket ws) {
            this.ws = ws;
        }

        void markDegraded() {
            degraded.set(true);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            WebSocket w = ws;
            if (w != null) {
                w.abort();
            }
        }

        @Override
        public boolean isConnected() {
            WebSocket w = ws;
            return w != null && !w.isInputClosed();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}