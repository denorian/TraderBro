package com.traderbro.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.traderbro.core.event.NotificationLevel;
import com.traderbro.core.event.NotificationType;
import com.traderbro.core.event.TraderEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the permanent dedup for divergence/angulation notifications (review 2026-11-01): a
 * confirmed divergence carries a stable natural key, so once queued/sent it must never be re-sent —
 * even across restarts or catch-up rescans.
 */
class NotificationServiceTest {

    @Test
    void skipsDivergenceAlreadyQueuedByDedupKey() {
        NotificationStore store = mock(NotificationStore.class);
        NotificationService svc = new NotificationService(true, NotificationLevel.INFO, store);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("figi", "BNBUSDT");
        payload.put("interval", "1ч");
        payload.put("aoPeaks", "3.24 (00:00) → 2.57 (06:00)");
        payload.put("priceAtPeaks", "754.22 → 754.37");
        TraderEvent e = TraderEvent.of(NotificationType.AO_DIVERGENCE_CONFIRMED,
                NotificationLevel.WARNING, payload);

        // First publish: not yet in DB → insert.
        when(store.existsWithDedupKey(any())).thenReturn(false);
        svc.publish(e);
        verify(store, times(1)).insert(any(NotificationRecord.class));

        // Second publish of the same natural event (e.g. catch-up after restart): already queued → skip.
        when(store.existsWithDedupKey(any())).thenReturn(true);
        svc.publish(e);
        verify(store, times(1)).insert(any(NotificationRecord.class));
        assertThat(svc.skippedCount()).isEqualTo(1);
    }
}