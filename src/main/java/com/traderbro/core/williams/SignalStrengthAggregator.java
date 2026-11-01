package com.traderbro.core.williams;

import com.traderbro.api.config.WilliamsProperties;
import com.traderbro.core.domain.AngulationEvent;
import com.traderbro.core.domain.DivergenceEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates repeated Bill Williams signals into a combined strength, per the practitioner's ask:
 * instead of the strength of a single divergence/angulation, report the total strength of all
 * signals that fired from the same price block over a window. A divergence at 06:00 plus a
 * stronger angulation at 23:00 from the same level are counted as multiple "arguments" from one
 * price point — the combined short/long strength grows with the number of confirmations.
 *
 * <p>Each confirmed divergence contributes its {@code strength} (0..1); an angulation anchored on
 * that divergence adds a {@code [0..1]} bonus from its geometric angle ({@code angleDeg / 90}).
 * Events are grouped by direction and then clustered into price blocks by {@code priceBlockPct}
 * proximity of the peak prices, so repeated hits at the same level accumulate.
 */
public class SignalStrengthAggregator {

    private final int windowBars;
    private final double priceBlockPct;

    public SignalStrengthAggregator(WilliamsProperties.Aggregation cfg) {
        this.windowBars = cfg.getWindowBars();
        this.priceBlockPct = cfg.getPriceBlockPct();
    }

    public int windowBars() {
        return windowBars;
    }

    /** Combined strength per (direction, price block), strongest first. */
    public List<BlockStrength> aggregate(List<DivergenceEvent> divergences,
                                         Map<UUID, AngulationEvent> angulations) {
        List<BlockStrength> out = new ArrayList<>();
        for (DivergenceEvent.Direction dir : DivergenceEvent.Direction.values()) {
            List<DivergenceEvent> d = divergences.stream()
                    .filter(e -> e.getDirection() == dir)
                    .toList();
            for (List<DivergenceEvent> block : clusterByPrice(d)) {
                double strength = 0.0;
                double priceLevel = 0.0;
                for (DivergenceEvent e : block) {
                    strength += e.getStrength();
                    AngulationEvent a = angulations.get(e.getId());
                    if (a != null) {
                        strength += bonus(a);
                    }
                    priceLevel += e.getPriceAtPeak2();
                }
                strength = round(strength);
                double level = block.isEmpty() ? 0 : priceLevel / block.size();
                out.add(new BlockStrength(dir, round(level), block.size(), strength));
            }
        }
        out.sort(Comparator.comparingDouble(BlockStrength::strength).reversed());
        return out;
    }

    /** Angulation adds an argument whose weight grows with its geometric angle (0..1). */
    private static double bonus(AngulationEvent a) {
        double v = a.getAngleDeg() / 90.0;
        return Math.min(1.0, Math.max(0.0, v));
    }

    /** Greedily clusters divergences whose peak prices are within {@code priceBlockPct}%. */
    private List<List<DivergenceEvent>> clusterByPrice(List<DivergenceEvent> events) {
        List<DivergenceEvent> sorted = events.stream()
                .sorted(Comparator.comparing(DivergenceEvent::getTs))
                .toList();
        List<List<DivergenceEvent>> clusters = new ArrayList<>();
        for (DivergenceEvent e : sorted) {
            List<DivergenceEvent> target = null;
            for (List<DivergenceEvent> c : clusters) {
                double anchor = c.get(0).getPriceAtPeak2();
                if (sameBlock(e.getPriceAtPeak2(), anchor)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                List<DivergenceEvent> nc = new ArrayList<>();
                nc.add(e);
                clusters.add(nc);
            } else {
                target.add(e);
            }
        }
        return clusters;
    }

    private boolean sameBlock(double price, double anchor) {
        if (anchor == 0.0) {
            return Math.abs(price) <= 1e-9;
        }
        return Math.abs(price - anchor) / Math.abs(anchor) * 100.0 <= priceBlockPct;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public record BlockStrength(DivergenceEvent.Direction direction, double priceLevel,
                                long signals, double strength) {
    }
}