package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.ses.SesRecipientEvent.Cause;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EventBridgeDestination;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.SnsDestination;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SesEventPublisher}'s PutEvents entry construction.
 *
 * <p>The integration suite ({@link SesEventPublishingV2IntegrationTest}) cannot
 * distinguish the with-fix and without-fix shapes of the EventBridge entry, because
 * {@code EventBridgeService.buildEventEnvelope} defensively defaults
 * a missing {@code Resources} entry to an empty {@code ArrayNode} when assembling
 * the delivered envelope. These mock-based tests inspect the entry handed to
 * {@code eventBridgeService.putEvents} directly so we can pin the publisher's
 * contract regardless of downstream defaulting.
 */
@ExtendWith(MockitoExtension.class)
class SesEventPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BUS_ARN =
            "arn:aws:events:us-east-1:000000000000:event-bus/default";
    private static final String ACCOUNT = "111122223333";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:" + ACCOUNT + ":ses-events";
    private static final String CONFIGURATION_SET_ARN =
            "arn:aws:ses:us-east-1:" + ACCOUNT + ":configuration-set/cs-test";
    private static final String DOMAIN_ARN = "arn:aws:ses:us-east-1:" + ACCOUNT + ":identity/example.com";

    @Mock private SnsService snsService;
    @Mock private FirehoseService firehoseService;
    @Mock private EventBridgeService eventBridgeService;
    @Mock private CloudWatchMetricsService cloudWatchMetricsService;
    @Mock private ServiceCallAuthorizer serviceCallAuthorizer;

    private SesEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SesEventPublisher(snsService, firehoseService, eventBridgeService,
                cloudWatchMetricsService, serviceCallAuthorizer, MAPPER);
    }

    @Test
    void anSnsEventDestinationPublishesAsSesForItsConfigurationSet() {
        when(serviceCallAuthorizer.authorize("ses", "sns:Publish", TOPIC_ARN, CONFIGURATION_SET_ARN, ACCOUNT))
                .thenReturn(Decision.ALLOW);

        publishSendEvent(configurationSetWithSnsDestination(TOPIC_ARN));

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), anyString(), isNull(), eq("us-east-1"));
    }

    @Test
    void anSnsEventDestinationWhoseTopicRefusesSesGetsNothing() {
        when(serviceCallAuthorizer.authorize("ses", "sns:Publish", TOPIC_ARN, CONFIGURATION_SET_ARN, ACCOUNT))
                .thenReturn(Decision.DENY);

        publishSendEvent(configurationSetWithSnsDestination(TOPIC_ARN));

        verify(snsService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void anIdentityNotificationPublishesAsSesForTheIdentityThatNamesTheTopic() {
        when(serviceCallAuthorizer.authorize("ses", "sns:Publish", TOPIC_ARN, DOMAIN_ARN, ACCOUNT))
                .thenReturn(Decision.ALLOW);
        when(snsService.topicExists(TOPIC_ARN, "us-east-1")).thenReturn(true);

        assertTrue(publishBounceNotification());

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), anyString(), isNull(), eq("us-east-1"));
    }

    @Test
    void anIdentityNotificationToATopicThatIsGoneIsNotSentAndSaysSo() {
        when(serviceCallAuthorizer.authorize("ses", "sns:Publish", TOPIC_ARN, DOMAIN_ARN, ACCOUNT))
                .thenReturn(Decision.ALLOW);
        when(snsService.topicExists(TOPIC_ARN, "us-east-1")).thenReturn(false);

        assertFalse(publishBounceNotification());

        verify(snsService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void anIdentityNotificationWhoseTopicRefusesSesIsNotSentAndSaysSo() {
        when(serviceCallAuthorizer.authorize("ses", "sns:Publish", TOPIC_ARN, DOMAIN_ARN, ACCOUNT))
                .thenReturn(Decision.DENY);

        assertFalse(publishBounceNotification());

        verify(snsService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void publishEventBridge_withNullSourceArn_emitsEmptyResourcesArrayNotAbsent() {
        // Regression pin: SesEventPublisher.publishEventBridge must always set the
        // "Resources" key on the PutEvents entry, even when sourceArn is null. Omitting
        // the key causes EventBridgeService.matchesPattern to NPE on
        // `((ArrayNode) entry.get("Resources")).elements()` for any rule with a
        // "resources" pattern filter — the exception is caught and the rule silently
        // turns into a no-match plus log noise. An empty ArrayNode matches AWS's
        // behavior (the resources field is always present, sometimes empty).
        when(eventBridgeService.putEvents(any(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));

        publisher.publish(configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN), SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", null, null, "000000000000", "subj", List.of("to@example.com"), null, null, List.of("to@example.com"), null, null, Instant.now(), "us-east-1");

        Map<String, Object> entry = captureSingleEntry();
        assertTrue(entry.containsKey("Resources"),
                "Resources key must be present on the entry even when sourceArn is null");
        Object resources = entry.get("Resources");
        assertInstanceOf(ArrayNode.class, resources,
                "Resources must be an ArrayNode (EventBridgeService.matchesPattern "
                + "casts entry.get(\"Resources\") unconditionally)");
        assertEquals(0, ((ArrayNode) resources).size(),
                "Resources must be an empty array when sourceArn is null, not absent");
    }

    @Test
    void publishEventBridge_withSourceArn_emitsResourcesContainingArn() {
        String arn = "arn:aws:ses:us-east-1:000000000000:identity/sender@example.com";
        when(eventBridgeService.putEvents(any(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));

        publisher.publish(configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN), SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-1", "sender@example.com", arn, "000000000000", "subj", List.of("to@example.com"), null, null, List.of("to@example.com"), null, null, Instant.now(), "us-east-1");

        Map<String, Object> entry = captureSingleEntry();
        Object resources = entry.get("Resources");
        assertNotNull(resources);
        assertInstanceOf(ArrayNode.class, resources);
        ArrayNode arr = (ArrayNode) resources;
        assertEquals(1, arr.size());
        assertEquals(arn, arr.get(0).asText());
    }

    @Test
    void publishEventBridge_failedEntries_doesNotThrow() {
        when(eventBridgeService.putEvents(any(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(1, List.of(Map.of("ErrorCode", "InternalFailure"))));

        // A failed PutEvents entry is logged by the publisher but must not propagate
        // back into the SES send path.
        assertDoesNotThrow(() -> publisher.publish(
                configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN),
                SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-2", null, null,
                "000000000000", "subj", List.of("to@example.com"), null, null,
                List.of("to@example.com"), null, null, Instant.now(), "us-east-1"));
        verify(eventBridgeService).putEvents(any(), anyString());
    }

    @Test
    void publishEventBridge_putEventsThrows_doesNotThrow() {
        when(eventBridgeService.putEvents(any(), anyString()))
                .thenThrow(new RuntimeException("event bridge unavailable"));

        // Downstream delivery failures must never fail the email send.
        assertDoesNotThrow(() -> publisher.publish(
                configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN),
                SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-3", null, null,
                "000000000000", "subj", List.of("to@example.com"), null, null,
                List.of("to@example.com"), null, null, Instant.now(), "us-east-1"));
        verify(eventBridgeService).putEvents(any(), anyString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureSingleEntry() {
        ArgumentCaptor<List<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), anyString());
        List<Map<String, Object>> entries = captor.getValue();
        assertEquals(1, entries.size(), "publisher must emit exactly one entry per send");
        return entries.get(0);
    }

    private void publishSendEvent(ConfigurationSet configurationSet) {
        publisher.publish(configurationSet, SesRecipientEvent.of("SEND", Cause.SIMULATOR, List.of()), "msg-sns",
                "sender@example.com", "arn:aws:ses:us-east-1:" + ACCOUNT + ":identity/sender@example.com",
                ACCOUNT, "subj", List.of("to@example.com"), null, null, List.of("to@example.com"), null, null,
                Instant.parse("2026-01-01T00:00:00Z"), "us-east-1");
    }

    /** A bounce from {@code sender@example.com}, whose domain names the topic. */
    private boolean publishBounceNotification() {
        return publisher.publishIdentityNotification(TOPIC_ARN, DOMAIN_ARN, false,
                SesRecipientEvent.of("BOUNCE", Cause.SIMULATOR, List.of("bounce@simulator.amazonses.com")),
                "msg-bounce", "sender@example.com", "arn:aws:ses:us-east-1:" + ACCOUNT + ":identity/sender@example.com",
                ACCOUNT, "subj", List.of("bounce@simulator.amazonses.com"), null, null,
                List.of("bounce@simulator.amazonses.com"), null, Instant.parse("2026-01-01T00:00:00Z"), "us-east-1");
    }

    private ConfigurationSet configurationSetWithSnsDestination(String topicArn) {
        SnsDestination snsDestination = new SnsDestination();
        snsDestination.setTopicArn(topicArn);
        EventDestination ed = new EventDestination();
        ed.setName("ed-sns");
        ed.setEnabled(true);
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setSnsDestination(snsDestination);
        ConfigurationSet cs = new ConfigurationSet("cs-test");
        cs.setEventDestinations(List.of(ed));
        return cs;
    }

    private ConfigurationSet configurationSetWithEventBridgeDestination(String busArn) {
        EventBridgeDestination ebd = new EventBridgeDestination();
        ebd.setEventBusArn(busArn);
        EventDestination ed = new EventDestination();
        ed.setName("ed-eb");
        ed.setEnabled(true);
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setEventBridgeDestination(ebd);
        ConfigurationSet cs = new ConfigurationSet("cs-test");
        cs.setEventDestinations(List.of(ed));
        return cs;
    }
}
