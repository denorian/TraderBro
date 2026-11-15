package com.traderbro.signalanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.traderbro.signalanalysis.domain.AvailabilityStatus;
import com.traderbro.signalanalysis.domain.ChangeType;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import com.traderbro.signalanalysis.ingestion.TelegramMessageSnapshotMapper;
import com.traderbro.signalanalysis.repository.TelegramMessageRepository;
import com.traderbro.signalanalysis.repository.TelegramMessageVersionRepository;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** Idempotency of snapshot apply, edit collapsing and the delete matrix (section 6.4). */
class TelegramMessageServiceTest {

    private TelegramMessageRepository messages;
    private TelegramMessageVersionRepository versions;
    private TelegramMessageService service;
    private AtomicReference<com.traderbro.signalanalysis.domain.TelegramMessage> current;
    private TelegramChannel channel;

    @BeforeEach
    void setUp() {
        messages = mock(TelegramMessageRepository.class);
        versions = mock(TelegramMessageVersionRepository.class);
        service = new TelegramMessageService(messages, versions,
                new TelegramMessageSnapshotMapper(new ObjectMapper()));
        current = new AtomicReference<>();
        when(messages.findByChannelAndMessageIdForUpdate(any(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(messages.insertCurrent(any())).thenAnswer(inv -> {
            current.set(inv.getArgument(0));
            return true;
        });
        channel = TelegramChannel.builder().id(UUID.randomUUID()).monitoringEnabled(true).build();
    }

    private TelegramMessage snap(long id, String text, Instant editedAt) {
        return new TelegramMessage(id, 1L, "USER", 1L, null, Instant.EPOCH, editedAt, null,
                new TelegramMessageContent("TEXT", text, null, null, null));
    }

    @Test
    void newMessageCreatesCurrentAndVersion1() {
        TelegramMessageService.ApplyResult r = service.apply(channel, snap(10, "hello", null),
                ChangeType.LIVE_NEW, Instant.now(), UUID.randomUUID());
        assertEquals(TelegramMessageService.ApplyResult.CREATED, r);
        assertFalse(current.get().isPermanentlyDeleted());
        assertEquals(1, current.get().getCurrentVersionNo());
        verify(versions).insert(any());
    }

    @Test
    void replayingSameSnapshotDoesNotDuplicate() {
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        TelegramMessageService.ApplyResult r2 = service.apply(channel, snap(10, "hello", null),
                ChangeType.BACKFILL, Instant.now(), UUID.randomUUID());
        assertEquals(TelegramMessageService.ApplyResult.NO_CHANGE, r2);
        assertEquals(1, current.get().getCurrentVersionNo());
    }

    @Test
    void twoUpdatesOfOneEditCollapseIntoASingleVersion() {
        Instant edit = Instant.now();
        service.apply(channel, snap(10, "v1", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        // First TDLib update of the physical edit.
        TelegramMessageService.ApplyResult a = service.apply(channel, snap(10, "v2", edit),
                ChangeType.EDIT, Instant.now(), UUID.randomUUID());
        // Second TDLib update (same physical edit, same full snapshot).
        TelegramMessageService.ApplyResult b = service.apply(channel, snap(10, "v2", edit),
                ChangeType.EDIT, Instant.now(), UUID.randomUUID());
        assertEquals(TelegramMessageService.ApplyResult.UPDATED, a);
        assertEquals(TelegramMessageService.ApplyResult.NO_CHANGE, b);
        assertEquals(2, current.get().getCurrentVersionNo());
    }

    @Test
    void twoDistinctEditsProduceSequentialVersions() {
        service.apply(channel, snap(10, "v1", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        service.apply(channel, snap(10, "v2", Instant.now()), ChangeType.EDIT, Instant.now(),
                UUID.randomUUID());
        service.apply(channel, snap(10, "v3", Instant.now()), ChangeType.EDIT, Instant.now(),
                UUID.randomUUID());
        assertEquals(3, current.get().getCurrentVersionNo());
    }

    @Test
    void returningToPreviousTextCreatesNewVersionDespiteSameHash() {
        Instant e1 = Instant.now();
        Instant e2 = e1.plusSeconds(10);
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        service.apply(channel, snap(10, "bye", e1), ChangeType.EDIT, Instant.now(),
                UUID.randomUUID());
        // Back to "hello" but at a later edit time -> distinct snapshot -> new version.
        TelegramMessageService.ApplyResult r = service.apply(channel, snap(10, "hello", e2),
                ChangeType.EDIT, Instant.now(), UUID.randomUUID());
        assertEquals(TelegramMessageService.ApplyResult.UPDATED, r);
        assertEquals(3, current.get().getCurrentVersionNo());
    }

    @Test
    void editAfterPermanentDeleteDoesNotResurrect() {
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        current.get().setPermanentlyDeleted(true);
        TelegramMessageService.ApplyResult r = service.apply(channel, snap(10, "edited", Instant.now()),
                ChangeType.EDIT, Instant.now(), UUID.randomUUID());
        assertEquals(TelegramMessageService.ApplyResult.SKIPPED_DELETED, r);
        assertEquals(1, current.get().getCurrentVersionNo());
    }

    // ------------------------------------------------------------------ delete matrix (6.4)

    @Test
    void cacheEvictionDoesNotMarkDeleted() {
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        service.applyDelete(channel, 10, true, true, Instant.now());
        verify(messages, never()).markAvailability(any(), anyLong(), any(), anyBoolean(),
                any(), any());
    }

    @Test
    void permanentDeleteMarksDeleted() {
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        service.applyDelete(channel, 10, true, false, Instant.now());
        ArgumentCaptor<AvailabilityStatus> cap = ArgumentCaptor.forClass(AvailabilityStatus.class);
        verify(messages).markAvailability(eq(channel.getId()), eq(10L), cap.capture(), eq(true),
                any(), any());
        assertEquals(AvailabilityStatus.DELETED, cap.getValue());
    }

    @Test
    void nonPermanentDeleteMarksInaccessible() {
        service.apply(channel, snap(10, "hello", null), ChangeType.LIVE_NEW, Instant.now(),
                UUID.randomUUID());
        service.applyDelete(channel, 10, false, false, Instant.now());
        ArgumentCaptor<AvailabilityStatus> cap = ArgumentCaptor.forClass(AvailabilityStatus.class);
        verify(messages).markAvailability(eq(channel.getId()), eq(10L), cap.capture(), eq(false),
                any(), any());
        assertEquals(AvailabilityStatus.INACCESSIBLE, cap.getValue());
    }
}