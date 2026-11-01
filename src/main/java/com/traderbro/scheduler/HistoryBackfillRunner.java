package com.traderbro.scheduler;

import com.traderbro.data.binance.CryptoHistoryLoader;
import com.traderbro.data.tbank.HistoryLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;

/**
 * Runs the per-provider startup history backfills (T-Bank and Binance) concurrently on separate
 * daemon threads, then blocks until both finish so the app starts with complete history. Each
 * loader self-checks its own {@code load-on-startup} flag and is a no-op when disabled; the crypto
 * loader is optional (absent when {@code crypto.enabled=false}).
 */
@Slf4j
public class HistoryBackfillRunner implements CommandLineRunner {

    private final HistoryLoader tbankLoader;
    private final ObjectProvider<CryptoHistoryLoader> cryptoLoaderProvider;
    /** Two daemon threads — one per provider — so the two backfills genuinely run in parallel. */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "history-backfill");
        t.setDaemon(true);
        return t;
    });

    public HistoryBackfillRunner(HistoryLoader tbankLoader,
                                 ObjectProvider<CryptoHistoryLoader> cryptoLoaderProvider) {
        this.tbankLoader = tbankLoader;
        this.cryptoLoaderProvider = cryptoLoaderProvider;
    }

    @Override
    public void run(String... args) {
        List<CompletableFuture<Void>> tasks = new ArrayList<>(2);
        tasks.add(CompletableFuture.runAsync(tbankLoader::backfill, executor));
        CryptoHistoryLoader cryptoLoader = cryptoLoaderProvider.getIfAvailable();
        if (cryptoLoader != null) {
            tasks.add(CompletableFuture.runAsync(cryptoLoader::backfill, executor));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        log.info("history backfill complete");
    }
}