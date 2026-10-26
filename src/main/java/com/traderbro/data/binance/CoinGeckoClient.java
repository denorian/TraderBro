package com.traderbro.data.binance;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin read-only client for the CoinGecko market-cap endpoint. Only used to refresh crypto
 * market capitalisation/rank once a day; if CoinGecko is unreachable the caller simply skips the
 * update (it must not block the quote stream).
 */
@Slf4j
public class CoinGeckoClient {

    /** CoinGecko coin id → Binance base asset for the tracked top-5. */
    private static final List<String> COIN_IDS = List.of(
            "bitcoin", "ethereum", "binancecoin", "solana", "ripple");

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public CoinGeckoClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Result of one coin's market data. */
    public record MarketCap(String figi, BigDecimal capUsd, int rank) {
    }

    /**
     * Fetches market cap + rank for the tracked coins. {@code figi} is derived as
     * {@code symbol.toUpperCase() + "USDT"} (valid for the top-5 pairs). Returns an empty list
     * on any failure — the caller should skip the update, not fail.
     */
    public List<MarketCap> fetchTopMarkets() {
        String ids = String.join(",", COIN_IDS);
        String url = baseUrl + "/coins/markets?vs_currency=usd&ids=" + ids;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("coingecko HTTP {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }
            JsonNode root = json.readTree(resp.body());
            List<MarketCap> out = new ArrayList<>();
            for (JsonNode coin : root) {
                String symbol = coin.path("symbol").asText();
                if (symbol.isBlank()) {
                    continue;
                }
                String figi = symbol.toUpperCase(Locale.ROOT) + "USDT";
                JsonNode cap = coin.path("market_cap");
                JsonNode rank = coin.path("market_cap_rank");
                if (cap.isMissingNode() || cap.isNull()) {
                    continue;
                }
                out.add(new MarketCap(figi, cap.decimalValue(), rank.asInt(0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("coingecko fetch failed: {}", e.getMessage());
            return List.of();
        }
    }
}