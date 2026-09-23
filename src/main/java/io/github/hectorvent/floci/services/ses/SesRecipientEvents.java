package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesRecipientEvent.Cause;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that decides which SES events a send produces, per recipient. Configuration-set
 * event publishing and the identity notifications both consume this, and anything else that
 * derives a per-recipient outcome from a send should too, so the mailbox-simulator and
 * suppression semantics have a single home.
 *
 * <p>A recipient on the account suppression list is dropped before the relay, so its outcome is
 * the suppression reason regardless of its address; a list-management opt-out is dropped the same
 * way but bounces under its own cause, since it is not on the suppression list. Otherwise the
 * mailbox simulator decides:
 * {@code success@} is delivered, {@code bounce@} and {@code suppressionlist@} both hard-bounce
 * (probed 2026-09-21: the latter is an ordinary {@code General} bounce whose diagnostic text names
 * the suppression, not a {@code Reject}), and {@code complaint@} is delivered and then complained
 * about. An ordinary recipient produces nothing beyond {@code SEND} here; whether it counts as
 * delivered is the caller's question. A rejected message publishes only {@code SEND} and
 * {@code REJECT}, since SES accepts it and then refuses the whole message.
 */
final class SesRecipientEvents {

    static final String REASON_BOUNCE = "BOUNCE";
    static final String REASON_COMPLAINT = "COMPLAINT";
    static final String REASON_LIST_OPT_OUT = "LIST_MANAGEMENT";
    // The reason SES reports on a Reject event; also the marker stored on the rejected record.
    static final String CONTENT_REJECT_REASON = "Bad content";

    private SesRecipientEvents() {}

    static List<SesRecipientEvent> classify(List<String> envelope, Map<String, String> suppressedReasons,
                                            boolean contentRejected) {
        List<SesRecipientEvent> events = new ArrayList<>();
        events.add(SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()));
        if (contentRejected) {
            events.add(SesRecipientEvent.of("REJECT", Cause.CONTENT_REJECTED, List.of()));
            return events;
        }
        // Insertion order fixes the publish order: simulator outcomes, then suppression, then opt-outs.
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String key : List.of(key("DELIVERY", Cause.SIMULATOR), key("BOUNCE", Cause.SIMULATOR),
                key("COMPLAINT", Cause.SIMULATOR), key("BOUNCE", Cause.ACCOUNT_SUPPRESSION),
                key("COMPLAINT", Cause.ACCOUNT_SUPPRESSION), key("BOUNCE", Cause.LIST_MANAGEMENT))) {
            groups.put(key, new ArrayList<>());
        }
        if (envelope != null) {
            for (String recipient : envelope) {
                if (recipient == null || recipient.isBlank()) {
                    continue;
                }
                String reason = suppressedReasons == null ? null : suppressedReasons.get(recipient);
                if (REASON_BOUNCE.equals(reason)) {
                    add(groups, "BOUNCE", Cause.ACCOUNT_SUPPRESSION, recipient);
                } else if (REASON_COMPLAINT.equals(reason)) {
                    add(groups, "COMPLAINT", Cause.ACCOUNT_SUPPRESSION, recipient);
                } else if (REASON_LIST_OPT_OUT.equals(reason)) {
                    add(groups, "BOUNCE", Cause.LIST_MANAGEMENT, recipient);
                } else if (SimulatorAddresses.isSuccess(recipient)) {
                    add(groups, "DELIVERY", Cause.SIMULATOR, recipient);
                } else if (SimulatorAddresses.isBounce(recipient) || SimulatorAddresses.isSuppressionList(recipient)) {
                    add(groups, "BOUNCE", Cause.SIMULATOR, recipient);
                } else if (SimulatorAddresses.isComplaint(recipient)) {
                    add(groups, "DELIVERY", Cause.SIMULATOR, recipient);
                    add(groups, "COMPLAINT", Cause.SIMULATOR, recipient);
                }
            }
        }
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;
            }
            String[] parts = group.getKey().split("/");
            events.add(SesRecipientEvent.of(parts[0], Cause.valueOf(parts[1]), group.getValue()));
        }
        return events;
    }

    private static String key(String eventType, Cause cause) {
        return eventType + "/" + cause;
    }

    private static void add(Map<String, List<String>> groups, String eventType, Cause cause, String recipient) {
        List<String> recipients = groups.get(key(eventType, cause));
        String trimmed = recipient.trim();
        if (!recipients.contains(trimmed)) {
            recipients.add(trimmed);
        }
    }
}
