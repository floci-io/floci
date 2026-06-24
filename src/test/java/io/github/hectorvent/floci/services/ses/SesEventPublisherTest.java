package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EventBridgeDestination;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock private SnsService snsService;
    @Mock private FirehoseService firehoseService;
    @Mock private EventBridgeService eventBridgeService;
    @Mock private CloudWatchMetricsService cloudWatchMetricsService;

    private SesEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SesEventPublisher(snsService, firehoseService, eventBridgeService,
                cloudWatchMetricsService, MAPPER);
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

        publisher.publish(configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN),
                "SEND", "msg-1", null, null, "000000000000", "subj",
                List.of("to@example.com"), null, null, List.of("to@example.com"),
                null, null, null, null, Instant.now(), "us-east-1");

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

        publisher.publish(configurationSetWithEventBridgeDestination(DEFAULT_BUS_ARN),
                "SEND", "msg-1", "sender@example.com", arn, "000000000000", "subj",
                List.of("to@example.com"), null, null, List.of("to@example.com"),
                null, null, null, null, Instant.now(), "us-east-1");

        Map<String, Object> entry = captureSingleEntry();
        Object resources = entry.get("Resources");
        assertNotNull(resources);
        assertInstanceOf(ArrayNode.class, resources);
        ArrayNode arr = (ArrayNode) resources;
        assertEquals(1, arr.size());
        assertEquals(arn, arr.get(0).asText());
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
