package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesRecipientEvent.Cause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SesRecipientEventsTest {

    private static List<String> types(List<SesRecipientEvent> events) {
        return events.stream().map(SesRecipientEvent::eventType).toList();
    }

    private static SesRecipientEvent find(List<SesRecipientEvent> events, String type, Cause cause) {
        return events.stream().filter(e -> e.eventType().equals(type) && e.cause() == cause)
                .findFirst().orElseThrow();
    }

    @Test
    void ordinaryRecipientProducesOnlySend() {
        assertEquals(List.of("SEND"),
                types(SesRecipientEvents.classify(List.of("user@example.com"), Map.of(), false)));
    }

    @Test
    void successSimulatorIsDelivered() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("success@simulator.amazonses.com"), Map.of(), false);

        assertEquals(List.of("SEND", "DELIVERY"), types(events));
        assertEquals(List.of("success@simulator.amazonses.com"),
                find(events, "DELIVERY", Cause.SIMULATOR).recipients());
    }

    @Test
    void bounceAndSuppressionListSimulatorsShareOneGeneralBounce() {
        // Probed 2026-09-21: suppressionlist@ is an ordinary hard bounce, not a Reject.
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("bounce@simulator.amazonses.com", "suppressionlist@simulator.amazonses.com"),
                Map.of(), false);

        assertEquals(List.of("SEND", "BOUNCE"), types(events));
        assertEquals(List.of("bounce@simulator.amazonses.com", "suppressionlist@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.SIMULATOR).recipients());
    }

    @Test
    void complaintSimulatorIsDeliveredThenComplainedAbout() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("complaint@simulator.amazonses.com"), Map.of(), false);

        assertEquals(List.of("SEND", "DELIVERY", "COMPLAINT"), types(events));
    }

    @Test
    void suppressedRecipientsBounceOrComplainUnderTheirReason() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("a@example.com", "b@example.com"),
                Map.of("a@example.com", "BOUNCE", "b@example.com", "COMPLAINT"), false);

        assertEquals(List.of("SEND", "BOUNCE", "COMPLAINT"), types(events));
        assertEquals(List.of("a@example.com"), find(events, "BOUNCE", Cause.ACCOUNT_SUPPRESSION).recipients());
        assertEquals(List.of("b@example.com"), find(events, "COMPLAINT", Cause.ACCOUNT_SUPPRESSION).recipients());
    }

    @Test
    void simulatorAndSuppressionBouncesAreSeparateEvents() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("bounce@simulator.amazonses.com", "blocked@example.com"),
                Map.of("blocked@example.com", "BOUNCE"), false);

        assertEquals(List.of("SEND", "BOUNCE", "BOUNCE"), types(events));
        assertEquals(List.of("bounce@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.SIMULATOR).recipients());
        assertEquals(List.of("blocked@example.com"),
                find(events, "BOUNCE", Cause.ACCOUNT_SUPPRESSION).recipients());
    }

    @Test
    void suppressionWinsOverTheSimulatorAddress() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("bounce@simulator.amazonses.com"),
                Map.of("bounce@simulator.amazonses.com", "COMPLAINT"), false);

        assertEquals(List.of("SEND", "COMPLAINT"), types(events));
        assertEquals(Cause.ACCOUNT_SUPPRESSION, events.get(1).cause());
    }

    @Test
    void labelledSimulatorAddressKeepsItsScenarioAndItsSpelling() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("bounce+order-1@simulator.amazonses.com"), Map.of(), false);

        assertEquals(List.of("bounce+order-1@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.SIMULATOR).recipients());
    }

    @Test
    void recipientsAreTrimmedAndDeduplicated() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of(" bounce@simulator.amazonses.com", "bounce@simulator.amazonses.com "), Map.of(), false);

        assertEquals(List.of("bounce@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.SIMULATOR).recipients());
    }

    @Test
    void rejectedMessagePublishesOnlySendAndReject() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("success@simulator.amazonses.com", "blocked@example.com"),
                Map.of("blocked@example.com", "BOUNCE"), true);

        assertEquals(List.of("SEND", "REJECT"), types(events));
        assertEquals(Cause.CONTENT_REJECTED, events.get(1).cause());
    }

    @Test
    void nullEnvelopeAndNullReasonsAreTolerated() {
        assertEquals(List.of("SEND"), types(SesRecipientEvents.classify(null, null, false)));
    }

    @Test
    void ordinaryAndSimulatorRecipientsShareTheSendButNotTheOutcome() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("user@example.com", "bounce@simulator.amazonses.com"), Map.of(), false);

        assertEquals(List.of("SEND", "BOUNCE"), types(events));
        assertEquals(List.of("bounce@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.SIMULATOR).recipients());
    }

    @Test
    void bounceSimulatorSuppressedForBouncesBouncesOnceUnderTheSuppression() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("bounce@simulator.amazonses.com"),
                Map.of("bounce@simulator.amazonses.com", "BOUNCE"), false);

        assertEquals(List.of("SEND", "BOUNCE"), types(events));
        assertEquals(List.of("bounce@simulator.amazonses.com"),
                find(events, "BOUNCE", Cause.ACCOUNT_SUPPRESSION).recipients());
    }

    @Test
    void complaintSimulatorAndSuppressedComplaintAreSeparateEvents() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("complaint@simulator.amazonses.com", "blocked@example.com"),
                Map.of("blocked@example.com", "COMPLAINT"), false);

        assertEquals(List.of("SEND", "DELIVERY", "COMPLAINT", "COMPLAINT"), types(events));
        assertEquals(List.of("complaint@simulator.amazonses.com"),
                find(events, "DELIVERY", Cause.SIMULATOR).recipients());
        assertEquals(List.of("complaint@simulator.amazonses.com"),
                find(events, "COMPLAINT", Cause.SIMULATOR).recipients());
        assertEquals(List.of("blocked@example.com"),
                find(events, "COMPLAINT", Cause.ACCOUNT_SUPPRESSION).recipients());
    }

    @Test
    void listManagementOptOutBouncesUnderItsOwnCause() {
        List<SesRecipientEvent> events = SesRecipientEvents.classify(
                List.of("unsub@example.com", "blocked@example.com"),
                Map.of("unsub@example.com", SesRecipientEvents.REASON_LIST_OPT_OUT,
                        "blocked@example.com", "BOUNCE"), false);

        assertEquals(List.of("SEND", "BOUNCE", "BOUNCE"), types(events));
        assertEquals(List.of("blocked@example.com"),
                find(events, "BOUNCE", Cause.ACCOUNT_SUPPRESSION).recipients());
        assertEquals(List.of("unsub@example.com"),
                find(events, "BOUNCE", Cause.LIST_MANAGEMENT).recipients());
    }
}
