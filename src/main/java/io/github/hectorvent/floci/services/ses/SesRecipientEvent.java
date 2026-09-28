package io.github.hectorvent.floci.services.ses;

import java.util.List;

/**
 * One event to publish for a send: the SES event type, why it happened, and the recipients it
 * concerns. A send yields one of these per (type, cause) pair, so a message that bounces for two
 * different reasons publishes two {@code BOUNCE} events, as SES does.
 */
record SesRecipientEvent(String eventType, Cause cause, List<String> recipients) {

    enum Cause {
        /** A mailbox-simulator address, or the plain delivery of an ordinary recipient. */
        SIMULATOR,
        /** The recipient was on the account-level suppression list and never left Floci. */
        ACCOUNT_SUPPRESSION,
        /** The recipient had opted out through list management and never left Floci. */
        LIST_MANAGEMENT,
        /** The whole message failed the content scan and was rejected after acceptance. */
        CONTENT_REJECTED
    }

    static SesRecipientEvent of(String eventType, Cause cause, List<String> recipients) {
        return new SesRecipientEvent(eventType, cause, List.copyOf(recipients));
    }
}
