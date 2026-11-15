package com.traderbro.signalanalysis.telegram;

/** Supergroup metadata relevant to channel discovery, independent of TDLib types. */
public record TelegramSupergroup(
        long id,
        boolean isChannel,
        String username,
        MembershipStatus membershipStatus) {
}