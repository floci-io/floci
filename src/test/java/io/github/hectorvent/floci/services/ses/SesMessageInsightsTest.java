package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesMessageInsightsTest {

    private static final Instant SENT_AT = Instant.parse("2026-09-21T10:00:00Z");

    private static List<EmailInsights> build(List<String> envelope, Map<String, String> suppressedReasons) {
        return SesMessageInsights.build(envelope,
                SesRecipientEvents.classify(envelope, suppressedReasons, false), SENT_AT);
    }

    private static List<String> types(EmailInsights insights) {
        return insights.events().stream().map(InsightsEvent::type).toList();
    }

    @Test
    void ordinaryRecipientIsReportedAsDelivered() {
        List<EmailInsights> insights = build(List.of("user@example.com"), Map.of());

        assertEquals(1, insights.size());
        assertEquals("user@example.com", insights.get(0).destination());
        assertEquals(List.of("SEND", "DELIVERY"), types(insights.get(0)));
    }

    @Test
    void eachRecipientGetsItsOwnTimeline() {
        List<EmailInsights> insights = build(List.of("bounce@simulator.amazonses.com", "user@example.com"), Map.of());

        assertEquals(2, insights.size());
        assertEquals(List.of("SEND", "BOUNCE"), types(insights.get(0)));
        assertEquals(List.of("SEND", "DELIVERY"), types(insights.get(1)));
    }

    @Test
    void ccAndBccRecipientsAreIncludedInEnvelopeOrder() {
        List<EmailInsights> insights = build(List.of("to@example.com", "cc@example.com", "bcc@example.com"), Map.of());

        assertEquals(List.of("to@example.com", "cc@example.com", "bcc@example.com"),
                insights.stream().map(EmailInsights::destination).toList());
    }

    @Test
    void duplicateRecipientsAreCollapsed() {
        List<EmailInsights> insights = build(List.of("dup@example.com", "dup@example.com"), Map.of());

        assertEquals(1, insights.size());
    }

    @Test
    void blankAndNullRecipientsAreSkipped() {
        List<String> addresses = new ArrayList<>();
        addresses.add("user@example.com");
        addresses.add(null);
        addresses.add("   ");

        List<EmailInsights> insights = build(addresses, Map.of());

        assertEquals(1, insights.size());
    }

    @Test
    void complaintSimulatorIsDeliveredBeforeItComplains() {
        List<EmailInsights> insights = build(List.of("complaint@simulator.amazonses.com"), Map.of());

        assertEquals(List.of("SEND", "DELIVERY", "COMPLAINT"), types(insights.get(0)));
    }

    @Test
    void suppressionListSimulatorBouncesWithTheSuppressionDiagnostic() {
        List<EmailInsights> insights = build(List.of("suppressionlist@simulator.amazonses.com"), Map.of());

        assertEquals(List.of("SEND", "BOUNCE"), types(insights.get(0)));
        InsightsBounce bounce = insights.get(0).events().get(1).details().bounce();
        assertEquals("General", bounce.bounceSubType());
        assertTrue(bounce.diagnosticCode().contains("suppressed address: suppressionlist@simulator.amazonses.com"));
    }

    @Test
    void rejectedMessageEndsEveryTimelineAtReject() {
        List<String> envelope = List.of("user@example.com", "success@simulator.amazonses.com");
        List<EmailInsights> insights = SesMessageInsights.build(envelope,
                SesRecipientEvents.classify(envelope, Map.of(), true), SENT_AT);

        assertEquals(2, insights.size());
        assertEquals(List.of("SEND", "REJECT"), types(insights.get(0)));
        assertEquals(List.of("SEND", "REJECT"), types(insights.get(1)));
    }

    @Test
    void listManagementOptOutBouncesWithoutADiagnostic() {
        List<EmailInsights> insights = build(List.of("unsub@example.com"),
                Map.of("unsub@example.com", SesRecipientEvents.REASON_LIST_OPT_OUT));

        assertEquals(List.of("SEND", "BOUNCE"), types(insights.get(0)));
        InsightsBounce bounce = insights.get(0).events().get(1).details().bounce();
        assertEquals("General", bounce.bounceSubType());
        assertNull(bounce.diagnosticCode());
    }

    @Test
    void labelledSimulatorAddressKeepsItsScenario() {
        List<EmailInsights> insights = build(List.of("bounce+order-1@simulator.amazonses.com"), Map.of());

        assertEquals(List.of("SEND", "BOUNCE"), types(insights.get(0)));
    }

    @Test
    void suppressedRecipientBouncesInsteadOfDelivering() {
        List<EmailInsights> insights = build(List.of("blocked@example.com"), Map.of("blocked@example.com", "BOUNCE"));

        assertEquals(List.of("SEND", "BOUNCE"), types(insights.get(0)));
    }

    @Test
    void suppressionReasonWinsOverTheSimulatorScenario() {
        List<EmailInsights> insights = build(List.of("bounce@simulator.amazonses.com"),
                Map.of("bounce@simulator.amazonses.com", "COMPLAINT"));

        assertEquals(List.of("SEND", "COMPLAINT"), types(insights.get(0)));
    }

    @Test
    void suppressedComplaintRecipientIsNeverDelivered() {
        // The message is dropped before the relay, unlike complaint@simulator which is delivered
        // and then complained about.
        List<EmailInsights> insights = build(List.of("blocked@example.com"),
                Map.of("blocked@example.com", "COMPLAINT"));

        assertEquals(List.of("SEND", "COMPLAINT"), types(insights.get(0)));
    }

    @Test
    void bounceCarriesTheSimulatorDiagnosticCode() {
        List<EmailInsights> insights = build(List.of("bounce@simulator.amazonses.com"), Map.of());

        InsightsEvent bounce = insights.get(0).events().get(1);
        assertEquals("PERMANENT", bounce.details().bounce().bounceType());
        assertEquals("General", bounce.details().bounce().bounceSubType());
        assertEquals("smtp; 550 5.1.1 user unknown", bounce.details().bounce().diagnosticCode());
        assertNull(bounce.details().complaint());
    }

    @Test
    void complaintCarriesAFeedbackType() {
        List<EmailInsights> insights = build(List.of("complaint@simulator.amazonses.com"), Map.of());

        InsightsEvent complaint = insights.get(0).events().get(2);
        assertEquals("abuse", complaint.details().complaint().complaintFeedbackType());
        // SES leaves ComplaintSubType unset unless the address was already suppressed.
        assertNull(complaint.details().complaint().complaintSubType());
        assertNull(complaint.details().bounce());
    }

    @Test
    void suppressedBounceCarriesTheSuppressionSubtypeAndDiagnostic() {
        List<EmailInsights> insights = build(List.of("blocked@example.com"), Map.of("blocked@example.com", "BOUNCE"));

        InsightsEvent bounce = insights.get(0).events().get(1);
        assertEquals("BOUNCE", bounce.type());
        assertEquals("PERMANENT", bounce.details().bounce().bounceType());
        // GetMessageInsights spells the subtype in upper snake case and carries SES's own
        // suppression text as the diagnostic (probed 2026-09-21).
        assertEquals("ON_ACCOUNT_SUPPRESSION_LIST", bounce.details().bounce().bounceSubType());
        assertEquals(SesEventPayload.ACCOUNT_SUPPRESSION_DIAGNOSTIC, bounce.details().bounce().diagnosticCode());
    }

    @Test
    void suppressedComplaintCarriesTheSuppressionSubtypeAndNoFeedbackType() {
        List<EmailInsights> insights = build(List.of("blocked@example.com"),
                Map.of("blocked@example.com", "COMPLAINT"));

        InsightsEvent complaint = insights.get(0).events().get(1);
        assertEquals("COMPLAINT", complaint.type());
        assertEquals("ON_ACCOUNT_SUPPRESSION_LIST", complaint.details().complaint().complaintSubType());
        // No ISP feedback loop fired, so there is no feedback type to report.
        assertNull(complaint.details().complaint().complaintFeedbackType());
    }

    @Test
    void plainEventsCarryNoDetails() {
        List<EmailInsights> insights = build(List.of("user@example.com"), Map.of());

        for (InsightsEvent event : insights.get(0).events()) {
            assertNull(event.details(), event.type() + " should have no Details");
        }
    }

    @Test
    void eventsAreOrderedInTimeSoAClientCanSortThem() {
        List<EmailInsights> insights = build(List.of("complaint@simulator.amazonses.com"), Map.of());

        List<InsightsEvent> events = insights.get(0).events();
        assertEquals(SENT_AT, events.get(0).timestamp());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).timestamp().isAfter(events.get(i - 1).timestamp()),
                    "event " + i + " must come after event " + (i - 1));
        }
    }

    @Test
    void ispIsAlwaysUnknownBecauseFlociCannotObserveIt() {
        List<EmailInsights> insights = build(List.of("user@example.com"), Map.of());

        assertEquals("UNKNOWN_ISP", insights.get(0).isp());
    }
}
