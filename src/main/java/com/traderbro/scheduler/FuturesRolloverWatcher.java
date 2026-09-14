package com.traderbro.scheduler;

import com.traderbro.api.config.FuturesProperties;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.spi.MarketDataProvider;
import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import com.traderbro.core.event.TraderEventPublisher;
import com.traderbro.core.futures.FuturesContractSelector;
import com.traderbro.storage.InstrumentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Watches traded futures for approaching expiry and, when within {@code rolloverDaysBefore},
 * emits a WARNING notification recommending a roll to the next contract. Auto-rollover is not
 * implemented in this stage.
 */
@Slf4j
@RequiredArgsConstructor
public class FuturesRolloverWatcher {

    private final MarketDataProvider marketData;
    private final InstrumentRepository instrumentRepository;
    private final FuturesProperties properties;
    private final TraderEventPublisher publisher;
    private final Consumer<String> auditSink;

    @Scheduled(cron = "${scheduler.futures-rollover-cron:0 0 9 * * *}", zone = "Europe/Moscow")
    public void check() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        List<Instrument> traded = instrumentRepository.findAllTradable().stream()
                .filter(Instrument::isFuture)
                .toList();
        for (Instrument inst : traded) {
            var spec = inst.getFutureSpec();
            if (spec == null || spec.getExpirationDate() == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, spec.getExpirationDate());
            if (daysLeft <= properties.getRolloverDaysBefore()) {
                String nextTicker = nextContract(inst, spec.getBasicAsset()).orElse("—");
                Map<String, Object> payload = new HashMap<>();
                payload.put("ticker", inst.getTicker());
                payload.put("basicAsset", spec.getBasicAsset());
                payload.put("expiry", spec.getExpirationDate().toString());
                payload.put("daysLeft", daysLeft);
                payload.put("nextTicker", nextTicker);
                publisher.publish(TraderEvent.of(NotificationType.FUTURES_ROLLOVER_WARNING,
                        NotificationLevel.WARNING, payload));
                auditSink.accept("rollover: " + inst.getTicker() + " expires in " + daysLeft
                        + " days, recommend rolling to " + nextTicker);
                log.warn("futures rollover: {} expires in {} days, roll to {}", inst.getTicker(),
                        daysLeft, nextTicker);
            }
        }
    }

    private java.util.Optional<Instrument> nextContract(Instrument current, String basicAsset) {
        try {
            List<Instrument> all = marketData.findAllFutures(List.of(basicAsset));
            return FuturesContractSelector.nearestAfter(all, basicAsset, current.getFutureSpec().getExpirationDate());
        } catch (RuntimeException e) {
            log.warn("rollover: cannot resolve next contract for {}: {}", basicAsset, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}