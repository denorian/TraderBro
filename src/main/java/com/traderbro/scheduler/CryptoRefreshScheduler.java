package com.traderbro.scheduler;

import com.traderbro.api.config.CryptoProperties;
import com.traderbro.data.binance.BinanceMarketDataProvider;
import com.traderbro.data.binance.CoinGeckoClient;
import com.traderbro.storage.InstrumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Daily crypto maintenance: re-resolves the tracked spot pairs from Binance {@code exchangeInfo}
 * (keeps the instrument cache fresh) and refreshes market cap/rank from CoinGecko. Runs on the
 * {@code crypto.coingecko.update-cron} schedule (08:00 UTC by default). A CoinGecko outage is
 * non-fatal — the cap update is skipped while instrument refresh still proceeds.
 */
@Slf4j
@RequiredArgsConstructor
public class CryptoRefreshScheduler {

    private final BinanceMarketDataProvider marketData;
    private final InstrumentRepository repository;
    private final CoinGeckoClient coinGecko;
    private final CryptoProperties props;

    @Scheduled(cron = "${crypto.coingecko.update-cron:0 0 8 * * *}", zone = "UTC")
    public void refresh() {
        try {
            List<com.traderbro.core.domain.Instrument> resolved =
                    marketData.findInstruments(props.getPairs());
            if (!resolved.isEmpty()) {
                repository.upsertAll(resolved);
                log.info("crypto instruments refreshed: {}", resolved.size());
            }
        } catch (RuntimeException e) {
            log.error("crypto instrument refresh failed: {}", e.getMessage());
        }
        if (props.getCoingecko().isEnabled()) {
            try {
                int updated = 0;
                for (CoinGeckoClient.MarketCap mc : coinGecko.fetchTopMarkets()) {
                    repository.updateMarketCap(mc.figi(), mc.capUsd(), mc.rank());
                    updated++;
                }
                log.info("crypto market cap updated for {} instruments", updated);
            } catch (RuntimeException e) {
                log.warn("crypto market-cap update failed (non-fatal): {}", e.getMessage());
            }
        }
    }
}