package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftApiEventPublisherTest {

    @Test
    void disabledEventBridgeInTheServiceCatalogPreventsPublishing() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eventbridge().enabled()).thenReturn(false);
        ServiceRegistry registry = new ServiceRegistry(new ResolvedServiceCatalog(config));
        EventBridgeService events = mock(EventBridgeService.class);
        RedshiftApiEventPublisher publisher = new RedshiftApiEventPublisher(events, new ObjectMapper(), registry,
                new RegionResolver("us-east-1", "111111111111"));

        publisher.publish("CreateCluster");

        verifyNoInteractions(events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesSuccessfulWriteInRequestAccountAndRegion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventBridgeService events = mock(EventBridgeService.class);
        ServiceRegistry registry = enabledRegistry();
        when(events.putEvents(anyList(), anyString(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));
        RedshiftApiEventPublisher publisher = new RedshiftApiEventPublisher(events, mapper, registry,
                new RegionResolver("cn-north-1", "111111111111"));

        publisher.publish("CreateEventSubscription");

        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(events).putEvents(entries.capture(), eq("cn-north-1"), eq("111111111111"));
        Map<String, Object> entry = entries.getValue().getFirst();
        assertEquals("aws.redshift", entry.get("Source"));
        assertEquals("AWS API Call via CloudTrail", entry.get("DetailType"));
        JsonNode detail = mapper.readTree((String) entry.get("Detail"));
        assertEquals(ServicePrincipals.of("redshift"), detail.path("eventSource").asText());
        assertEquals("CreateEventSubscription", detail.path("eventName").asText());
        assertEquals("cn-north-1", detail.path("awsRegion").asText());
        assertEquals("111111111111", detail.path("recipientAccountId").asText());
        assertEquals("AwsApiCall", detail.path("eventType").asText());
        assertTrue(detail.path("managementEvent").asBoolean());
        assertFalse(detail.path("readOnly").asBoolean());
        assertDoesNotThrow(() -> Instant.parse(detail.path("eventTime").asText()));
        assertFalse(detail.path("eventID").asText().isBlank());
        assertFalse(detail.has("requestParameters"));
        assertFalse(detail.has("responseElements"));
        assertTrue(mapper.valueToTree(entry.get("Resources")).isEmpty());
    }

    @Test
    void disabledEventBridgeAndReadOperationsDoNotPublish() {
        EventBridgeService events = mock(EventBridgeService.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        RedshiftApiEventPublisher publisher = new RedshiftApiEventPublisher(events, new ObjectMapper(), registry,
                new RegionResolver("us-east-1", "111111111111"));

        publisher.publish("CreateCluster");
        when(registry.isServiceEnabled("events")).thenReturn(true);
        publisher.publish("DescribeEvents");
        publisher.publish("ListRecommendations");
        publisher.publish("GetClusterCredentials");

        verifyNoInteractions(events);
    }

    @Test
    void deliveryFailuresDoNotFailSuccessfulRedshiftOperation() {
        EventBridgeService events = mock(EventBridgeService.class);
        when(events.putEvents(anyList(), anyString(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(1,
                        List.of(Map.of("ErrorCode", "InternalFailure"))))
                .thenThrow(new AwsException("ServiceUnavailableException", "unavailable", 503))
                .thenThrow(new IllegalStateException("unavailable"));
        RedshiftApiEventPublisher publisher = new RedshiftApiEventPublisher(events, new ObjectMapper(),
                enabledRegistry(), new RegionResolver("us-east-1", "111111111111"));

        assertDoesNotThrow(() -> publisher.publish("DeleteEventSubscription"));
        assertDoesNotThrow(() -> publisher.publish("DeleteEventSubscription"));
        assertDoesNotThrow(() -> publisher.publish("DeleteEventSubscription"));
    }

    private static ServiceRegistry enabledRegistry() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.isServiceEnabled("events")).thenReturn(true);
        return registry;
    }
}
