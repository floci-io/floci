package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesRecipientEvent.Cause;
import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsComplaint;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the per-recipient event timelines that {@code GetMessageInsights} serves from the events
 * {@link SesRecipientEvents#classify} produced for the send, so the simulator and suppression
 * semantics have the same home as event publishing.
 *
 * <p>Insights add one thing publishing does not: {@code DELIVERY} for an ordinary recipient that
 * was neither suppressed nor a simulator address, so a plain send does not look stuck at
 * {@code SEND}. Floci hands such a message to the SMTP relay or drops it, and either way nothing
 * downstream can report a real outcome.
 *
 * <p>Floci tracks more messages than real SES does, deliberately. AWS documents that Virtual
 * Deliverability Manager ignores mail sent to the mailbox simulator, mail with more than one
 * recipient, and mail from a delegate sender. Both of the first two would gut the operation here:
 * simulator addresses are the only sends Floci can derive bounce and complaint events for, and
 * dropping multi-recipient mail would leave most local test traffic invisible. Floci therefore
 * records every send and returns one entry per recipient, where an AWS response in practice carries
 * exactly one. Both exclusions are probe-confirmed (2026-09-21): three simulator sends and one
 * two-recipient send were still absent from GetMessageInsights an hour later, while a
 * single-recipient send made in the middle of that window was already being served, which rules
 * out a merely slow indexing pipeline.
 */
final class SesMessageInsights {

    // Events within one recipient's timeline are one millisecond apart, the finest step the
    // epoch-seconds rendering keeps, so a client that sorts by timestamp keeps the order SES would
    // have produced while no event is stamped later than the send call that returns them. A single
    // instant for every event, which is what the data really supports, would make the ordering
    // arbitrary.
    private static final Duration EVENT_SPACING = Duration.ofMillis(1);

    // What real SES reports when it cannot identify the recipient's provider, which is always the
    // case here: Floci never sees a receiving mail server.
    private static final String UNKNOWN_ISP = "UNKNOWN_ISP";

    // BounceType is an enum in the Smithy model (UNDETERMINED / TRANSIENT / PERMANENT), so it is
    // upper case, while BounceSubType and the complaint members are free strings. GetMessageInsights
    // spells the suppression subtype in upper snake case (probed 2026-09-21 for the bounce; the
    // complaint follows by analogy), unlike the OnAccountSuppressionList of the published events.
    static final String ON_ACCOUNT_SUPPRESSION_LIST = "ON_ACCOUNT_SUPPRESSION_LIST";
    // ComplaintSubType is null for an ordinary feedback-loop complaint; SES only sets it for a
    // complaint raised against an address already on the suppression list.
    private static final InsightsComplaint SIMULATED_COMPLAINT = new InsightsComplaint(null, "abuse");
    private static final InsightsComplaint SUPPRESSED_COMPLAINT =
            new InsightsComplaint(ON_ACCOUNT_SUPPRESSION_LIST, null);

    private SesMessageInsights() {}

    /**
     * Builds one {@link EmailInsights} per envelope recipient, in envelope order with blanks and
     * duplicates removed, from the events the classifier derived for that envelope.
     */
    static List<EmailInsights> build(List<String> envelope, List<SesRecipientEvent> events, Instant sentAt) {
        Set<String> recipients = new LinkedHashSet<>();
        if (envelope != null) {
            for (String address : envelope) {
                if (address != null && !address.isBlank()) {
                    recipients.add(address);
                }
            }
        }
        List<EmailInsights> insights = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            insights.add(new EmailInsights(recipient, UNKNOWN_ISP, eventsFor(recipient, events, sentAt)));
        }
        return insights;
    }

    private static List<InsightsEvent> eventsFor(String recipient, List<SesRecipientEvent> events,
                                                 Instant sentAt) {
        List<InsightsEvent> timeline = new ArrayList<>();
        timeline.add(InsightsEvent.of(sentAt, "SEND"));
        String trimmed = recipient.trim();
        boolean outcome = false;
        for (SesRecipientEvent event : events) {
            switch (event.eventType()) {
                case "REJECT" -> {
                    // The whole message is refused, so every recipient's timeline ends here.
                    timeline.add(InsightsEvent.of(next(sentAt, timeline), "REJECT"));
                    outcome = true;
                }
                case "DELIVERY" -> {
                    if (event.recipients().contains(trimmed)) {
                        timeline.add(InsightsEvent.of(next(sentAt, timeline), "DELIVERY"));
                        outcome = true;
                    }
                }
                case "BOUNCE" -> {
                    if (event.recipients().contains(trimmed)) {
                        timeline.add(new InsightsEvent(next(sentAt, timeline), "BOUNCE",
                                InsightsEventDetails.ofBounce(bounceDetails(trimmed, event.cause()))));
                        outcome = true;
                    }
                }
                case "COMPLAINT" -> {
                    if (event.recipients().contains(trimmed)) {
                        timeline.add(new InsightsEvent(next(sentAt, timeline), "COMPLAINT",
                                InsightsEventDetails.ofComplaint(complaintDetails(event.cause()))));
                        outcome = true;
                    }
                }
                default -> {
                    // SEND is already on the timeline.
                }
            }
        }
        if (!outcome) {
            timeline.add(InsightsEvent.of(next(sentAt, timeline), "DELIVERY"));
        }
        return timeline;
    }

    private static Instant next(Instant sentAt, List<InsightsEvent> timeline) {
        return sentAt.plus(EVENT_SPACING.multipliedBy(timeline.size()));
    }

    private static InsightsBounce bounceDetails(String recipient, Cause cause) {
        return switch (cause) {
            case ACCOUNT_SUPPRESSION -> new InsightsBounce("PERMANENT", ON_ACCOUNT_SUPPRESSION_LIST,
                    SesEventPayload.ACCOUNT_SUPPRESSION_DIAGNOSTIC);
            // The shape of a list-management opt-out has not been probed, so it carries no diagnostic.
            case LIST_MANAGEMENT -> new InsightsBounce("PERMANENT", "General", null);
            default -> new InsightsBounce("PERMANENT", "General",
                    SesEventPayload.bounceDiagnosticCode(recipient, false));
        };
    }

    private static InsightsComplaint complaintDetails(Cause cause) {
        return cause == Cause.ACCOUNT_SUPPRESSION ? SUPPRESSED_COMPLAINT : SIMULATED_COMPLAINT;
    }
}
