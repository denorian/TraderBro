package com.traderbro.signalanalysis.telegram;

/** Membership of the personal account in a chat, mapped from TDLib {@code ChatMemberStatus}. */
public enum MembershipStatus {
    MEMBER,
    ADMINISTRATOR,
    CREATOR,
    RESTRICTED,
    LEFT,
    BANNED,
    UNKNOWN;

    /** True when the status implies the account is actually subscribed to the chat. */
    public boolean isActiveSubscription() {
        return this == MEMBER || this == ADMINISTRATOR || this == CREATOR || this == RESTRICTED;
    }
}