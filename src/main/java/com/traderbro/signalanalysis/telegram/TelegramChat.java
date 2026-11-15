package com.traderbro.signalanalysis.telegram;

/**
 * Broadcast-channel snapshot used by discovery, independent of any TDLib type. Only broadcast
 * channels ({@code isChannel}) are surfaced by discovery; ordinary groups/private/secret chats
 * are filtered out earlier.
 */
public record TelegramChat(
        long chatId,
        Long supergroupId,
        String title,
        String username,
        boolean isChannel,
        boolean archived,
        MembershipStatus membershipStatus) {
}