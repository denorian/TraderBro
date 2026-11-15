package com.traderbro.signalanalysis.api;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.SignalIngestionLifecycle;
import com.traderbro.signalanalysis.domain.SignalIngestionStatus;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramChannelSyncState;
import com.traderbro.signalanalysis.repository.TelegramIngestEventRepository;
import com.traderbro.signalanalysis.service.TelegramChannelQueryService;
import com.traderbro.signalanalysis.service.TelegramMonitoringService;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the stage 4.0 signal-analysis section. Everything is read-only except the
 * monitoring flag and the manual discovery trigger. Guarded by the shared {@code X-Api-Key} filter
 * (as with all {@code /api/**} endpoints). Works even when ingestion is disabled.
 */
@RestController
@RequestMapping("/api/signal-analysis")
public class SignalAnalysisController {

    private static final Set<String> ALLOWED_SORT = Set.of("title", "lastMessageAt", "lastSyncAt");

    private final TelegramChannelQueryService channels;
    private final TelegramMonitoringService monitoring;
    private final TelegramIngestEventRepository events;
    private final TelegramSessionManager session;
    private final SignalAnalysisProperties props;
    private final ObjectProvider<SignalIngestionLifecycle> lifecycle;

    public SignalAnalysisController(TelegramChannelQueryService channels,
                                    TelegramMonitoringService monitoring,
                                    TelegramIngestEventRepository events,
                                    TelegramSessionManager session,
                                    SignalAnalysisProperties props,
                                    ObjectProvider<SignalIngestionLifecycle> lifecycle) {
        this.channels = channels;
        this.monitoring = monitoring;
        this.events = events;
        this.session = session;
        this.props = props;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/channels")
    public ResponseEntity<ChannelPageDto> channels(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "true") Boolean subscribed,
            @RequestParam(required = false) Boolean monitoring,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) SignalIngestionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "title") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        int safeSize = Math.min(Math.max(size, 1), props.getApi().getMaxPageSize());
        int safePage = Math.max(page, 0);
        String safeSort = ALLOWED_SORT.contains(sort) ? sort : "title";
        TelegramChannelQueryService.Page result = channels.page(query, subscribed, monitoring,
                archived, status, safePage, safeSize, safeSort, direction);
        List<SignalChannelDto> items = result.items().stream().map(this::toDto).toList();
        return ResponseEntity.ok(new ChannelPageDto(items, result.total(), safePage, safeSize));
    }

    @PatchMapping("/channels/{id}/monitoring")
    public ResponseEntity<?> setMonitoring(@PathVariable UUID id,
                                           @RequestBody MonitoringUpdateRequest request) {
        try {
            TelegramChannel updated = monitoring.setMonitoring(id, request.enabled())
                    .orElse(null);
            if (updated == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorDto("Channel not found: " + id));
            }
            return ResponseEntity.ok(toDto(updated));
        } catch (TelegramMonitoringService.UnsubscribedChannelException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorDto("Channel is not subscribed, cannot enable monitoring"));
        }
    }

    @PatchMapping("/channels/monitoring")
    public ResponseEntity<?> setMonitoringBulk(@RequestBody MonitoringBulkRequest request) {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        if (request.channelIds() != null) {
            for (String id : request.channelIds()) {
                try {
                    ids.add(UUID.fromString(id));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest()
                            .body(new ErrorDto("Invalid channel id: " + id));
                }
            }
        }
        TelegramMonitoringService.BulkResult result =
                monitoring.setMonitoringBulk(ids, request.enabled());
        return ResponseEntity.ok(new MonitoringBulkResultDto(
                result.applied(), result.skippedUnsubscribed(), result.notFound()));
    }

    @PostMapping("/channels/sync")
    public ResponseEntity<?> syncChannels() {
        SignalIngestionLifecycle lc = lifecycle.getIfAvailable();
        if (lc == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorDto("Telegram ingestion is disabled"));
        }
        String runId = lc.triggerDiscovery();
        if (runId == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorDto("Ingestion not running"));
        }
        return ResponseEntity.accepted().body(new SyncRunResponse(runId));
    }

    @GetMapping("/ingestion/status")
    public IngestionStatusDto ingestionStatus() {
        SignalIngestionLifecycle lc = lifecycle.getIfAvailable();
        boolean enabled = props.getIngestion().isEnabled();
        boolean active = lc != null && lc.isActive();
        return new IngestionStatusDto(
                enabled,
                session.get(),
                active,
                lc == null ? null : lc.lastHeartbeat(),
                lc == null ? null : lc.lastDiscoveryAt(),
                events.pendingCount(),
                events.failedCount());
    }

    private SignalChannelDto toDto(TelegramChannelQueryService.Projection p) {
        TelegramChannel c = p.channel();
        TelegramChannelSyncState s = p.syncState();
        return new SignalChannelDto(
                c.getId().toString(),
                String.valueOf(c.getTelegramChatId()),
                c.getTitle(),
                c.getUsername(),
                c.isSubscribed(),
                c.isArchived(),
                c.getMembershipStatus(),
                c.isMonitoringEnabled(),
                p.ingestionStatus(),
                p.lastMessageAt(),
                s != null ? s.getLastSuccessfulSyncAt() : c.getSubscriptionSyncedAt(),
                p.messageCount(),
                p.editedCount(),
                p.deletedCount(),
                s != null ? s.getLastErrorCode() : null);
    }

    private SignalChannelDto toDto(TelegramChannel c) {
        return new SignalChannelDto(
                c.getId().toString(),
                String.valueOf(c.getTelegramChatId()),
                c.getTitle(),
                c.getUsername(),
                c.isSubscribed(),
                c.isArchived(),
                c.getMembershipStatus(),
                c.isMonitoringEnabled(),
                monitoring.computeStatus(c, null),
                null,
                c.getSubscriptionSyncedAt(),
                0, 0, 0,
                null);
    }

    /** Paginated wrapper for the channel list. */
    public record ChannelPageDto(List<SignalChannelDto> items, long total, int page, int size) {
    }

    /** Generic error envelope. */
    public record ErrorDto(String error) {
    }
}