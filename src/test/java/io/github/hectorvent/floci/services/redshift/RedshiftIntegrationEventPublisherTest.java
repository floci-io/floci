package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.redshift.RedshiftIntegrationEventPublisher.IntegrationEvent;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftIntegrationEventPublisherTest {

    private static final String ARN = "arn:aws:redshift:eu-west-1:222233334444:integration:abc";
    private static final Instant AT = Instant.parse("2026-10-28T11:30:15Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private EventBridgeService events;
    private ServiceRegistry registry;
    private RedshiftIntegrationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        events = mock(EventBridgeService.class);
        registry = mock(ServiceRegistry.class);
        when(registry.isServiceEnabled("events")).thenReturn(true);
        publisher = new RedshiftIntegrationEventPublisher(events, mapper,
                new RegionResolver("us-east-1", "111111111111"), registry);
    }

    private static Integration integration() {
        Integration integration = new Integration();
        integration.setIntegrationArn(ARN);
        integration.setIntegrationName("orders-to-warehouse");
        integration.setAccountId("222233334444");
        return integration;
    }

    @Test
    void publishesTheAwsTableRowAsAnAwsRedshiftEvent() throws Exception {
        publisher.queue(IntegrationEvent.CREATED_ACTIVE, integration(), "eu-west-1", AT);
        publisher.publishQueued();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(events).publishServiceEvent(eq("aws.redshift"), eq("Redshift Integration Monitoring"),
                detail.capture(), eq(ARN), eq("eu-west-1"));
        JsonNode body = mapper.readTree(detail.getValue());
        assertEquals("REDSHIFT-INTEGRATION-EVENT-0000", body.path("eventId").asText());
        assertEquals("INFO", body.path("severity").asText());
        assertEquals("Monitoring", body.path("category").asText());
        assertEquals(ARN, body.path("integrationArn").asText());
        assertEquals("Your zero-ETL Integration orders-to-warehouse has been created and became ACTIVE at "
                + "2026-10-28 11:30 UTC.", body.path("message").asText());
    }

    @Test
    void failedEventIsAnErrorWithTheTablesMessage() throws Exception {
        publisher.queue(IntegrationEvent.FAILED, integration(), "eu-west-1", AT);
        publisher.publishQueued();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(events).publishServiceEvent(eq("aws.redshift"), eq("Redshift Integration Monitoring"),
                detail.capture(), eq(ARN), eq("eu-west-1"));
        JsonNode body = mapper.readTree(detail.getValue());
        assertEquals("REDSHIFT-INTEGRATION-EVENT-0007", body.path("eventId").asText());
        assertEquals("ERROR", body.path("severity").asText());
        assertTrue(body.path("message").asText().startsWith("Unable to load data due to an internal error."));
    }

    @Test
    void nothingIsPublishedUntilTheQueueIsFlushed() {
        publisher.queue(IntegrationEvent.SYNCHRONIZING, integration(), "eu-west-1", AT);

        verify(events, never()).publishServiceEvent(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void eventsGoOutInTheOrderTheyWereQueued() {
        publisher.queue(IntegrationEvent.DELETION_INITIATED, integration(), "eu-west-1", AT);
        publisher.queue(IntegrationEvent.DELETED, integration(), "eu-west-1", AT);
        publisher.publishQueued();

        InOrder order = inOrder(events);
        order.verify(events).publishServiceEvent(eq("aws.redshift"), eq("Redshift Integration Monitoring"),
                contains("EVENT-0002"), eq(ARN), eq("eu-west-1"));
        order.verify(events).publishServiceEvent(eq("aws.redshift"), eq("Redshift Integration Monitoring"),
                contains("EVENT-0001"), eq(ARN), eq("eu-west-1"));
    }

    @Test
    void aDisabledEventBridgeDropsTheEventWithoutFailing() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eventbridge().enabled()).thenReturn(false);
        ServiceRegistry disabledRegistry = new ServiceRegistry(new ResolvedServiceCatalog(config));
        publisher = new RedshiftIntegrationEventPublisher(events, mapper,
                new RegionResolver("us-east-1", "111111111111"), disabledRegistry);

        publisher.queue(IntegrationEvent.ACTIVE, integration(), "eu-west-1", AT);
        publisher.publishQueued();

        verify(events, never()).publishServiceEvent(anyString(), anyString(), anyString(), any(), anyString());
    }
}
