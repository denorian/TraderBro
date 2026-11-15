package com.traderbro.signalanalysis.service;

import com.traderbro.signalanalysis.domain.SignalIngestionStatus;
import com.traderbro.signalanalysis.domain.TelegramChannel;
import com.traderbro.signalanalysis.domain.TelegramChannelSyncState;
import com.traderbro.signalanalysis.repository.TelegramChannelQueryRepository;
import com.traderbro.signalanalysis.repository.TelegramChannelSyncStateRepository;
import com.traderbro.signalanalysis.repository.TelegramMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds the paginated UI channel list with aggregate counts, last-message time, sync state and
 * the computed ingestion status. Counts and timestamps come from a small number of aggregate
 * queries — never one query per channel (no N+1).
 */
@Service
public class TelegramChannelQueryService {

    private final TelegramChannelQueryRepository query;
    private final TelegramMessageRepository messages;
    private final TelegramChannelSyncStateRepository states;
    private final TelegramMonitoringService monitoring;

    public TelegramChannelQueryService(TelegramChannelQueryRepository query,
                                       TelegramMessageRepository messages,
                                       TelegramChannelSyncStateRepository states,
                                       TelegramMonitoringService monitoring) {
        this.query = query;
        this.messages = messages;
        this.states = states;
        this.monitoring = monitoring;
    }

    /** Enriched, paginated channel projection. */
    public record Projection(TelegramChannel channel, TelegramChannelSyncState syncState,
                             long messageCount, long editedCount, long deletedCount,
                             Instant lastMessageAt, SignalIngestionStatus ingestionStatus) {
    }

    public record Page(List<Projection> items, long total) {
    }

    public Page page(String text, Boolean subscribed, Boolean monitoring, Boolean archived,
                     SignalIngestionStatus status, int page, int size, String sort,
                     String direction) {
        TelegramChannelQueryRepository.Page db = query.findPage(text, subscribed, monitoring,
                archived, page, size, sort, direction);
        List<TelegramChannel> rows = db.items();
        Map<UUID, TelegramMessageRepository.ChannelCounts> counts = messages.countsByChannel();
        Map<UUID, Instant> lastMessageAt = messages.lastMessageAtByChannel();
        Map<UUID, TelegramChannelSyncState> stateMap = states.findByChannelIds(
                rows.stream().map(TelegramChannel::getId).toList());

        List<Projection> projected = rows.stream()
                .map(c -> {
                    TelegramMessageRepository.ChannelCounts cc =
                            counts.getOrDefault(c.getId(), new TelegramMessageRepository.ChannelCounts(0, 0, 0));
                    TelegramChannelSyncState st = stateMap.get(c.getId());
                    return new Projection(c, st, cc.messageCount(), cc.editedCount(),
                            cc.deletedCount(), lastMessageAt.get(c.getId()),
                            this.monitoring.computeStatus(c, st));
                })
                .filter(p -> status == null || p.ingestionStatus() == status)
                .toList();
        return new Page(projected, db.total());
    }
}