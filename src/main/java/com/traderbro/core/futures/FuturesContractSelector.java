package com.traderbro.core.futures;

import com.traderbro.core.domain.FutureSpec;
import com.traderbro.core.domain.Instrument;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Selects the nearest tradable futures contract per underlying asset: the contract whose
 * expiry is soonest but not earlier than {@code minDaysToExpiry} days from {@code today}.
 * Pure and SDK-free, so it is unit-testable.
 */
public final class FuturesContractSelector {

    private FuturesContractSelector() {
    }

    /**
     * @param futures        all candidate futures for the requested basic assets
     * @param basicAssets    underlying asset codes to keep (e.g. IMOEX, Si, BR)
     * @param today          reference date
     * @param minDaysToExpiry skip contracts expiring within this many days
     * @return nearest eligible contract per basic asset, deterministically ordered
     */
    public static List<Instrument> selectNearest(List<Instrument> futures, List<String> basicAssets,
                                                 LocalDate today, int minDaysToExpiry) {
        LocalDate earliest = today.plusDays(minDaysToExpiry);
        List<Instrument> result = new ArrayList<>();
        for (String asset : basicAssets) {
            nearestFor(futures, asset, earliest).ifPresent(result::add);
        }
        return result;
    }

    /** Nearest eligible contract for a single basic asset. */
    public static Optional<Instrument> nearestFor(List<Instrument> futures, String basicAsset,
                                                  LocalDate earliest) {
        return futures.stream()
                .filter(Instrument::isFuture)
                .filter(i -> i.getFutureSpec() != null)
                .filter(i -> basicAsset.equals(i.getFutureSpec().getBasicAsset()))
                .filter(i -> i.getFutureSpec().getExpirationDate() != null)
                .filter(i -> !i.getFutureSpec().getExpirationDate().isBefore(earliest))
                .min(Comparator
                        .comparing((Instrument i) -> i.getFutureSpec().getExpirationDate())
                        .thenComparing(Instrument::getTicker));
    }

    /** Nearest contract for a basic asset expiring strictly after {@code afterDate} (for rollover). */
    public static Optional<Instrument> nearestAfter(List<Instrument> futures, String basicAsset,
                                                    LocalDate afterDate) {
        return futures.stream()
                .filter(Instrument::isFuture)
                .filter(i -> i.getFutureSpec() != null)
                .filter(i -> basicAsset.equals(i.getFutureSpec().getBasicAsset()))
                .filter(i -> i.getFutureSpec().getExpirationDate() != null)
                .filter(i -> i.getFutureSpec().getExpirationDate().isAfter(afterDate))
                .min(Comparator
                        .comparing((Instrument i) -> i.getFutureSpec().getExpirationDate())
                        .thenComparing(Instrument::getTicker));
    }
}