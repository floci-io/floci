package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.redshift.RedshiftIntegrationEventPublisher.IntegrationEvent;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftIntegrationEventsIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    RedshiftIntegrationEventPublisher publisher;
    @Inject
    EventBridgeService events;
    @Inject
    SqsService sqs;
    @Inject
    ObjectMapper mapper;

    @Test
    void integrationEventReachesOnlyTheIntegrationsAccountRule() throws Exception {
        String name = "rs-integration-event-" + UUID.randomUUID();
        String pattern = mapper.writeValueAsString(Map.of("source", List.of("aws.redshift"),
                "detail-type", List.of("Redshift Integration Monitoring"),
                "detail", Map.of("eventId", List.of("REDSHIFT-INTEGRATION-EVENT-0000"))));
        String queue = createRuleAndQueue(ACCOUNT, name, pattern);
        String otherQueue = createRuleAndQueue(OTHER_ACCOUNT, name, pattern);
        Integration integration = new Integration();
        integration.setAccountId(ACCOUNT);
        integration.setIntegrationName(name);
        integration.setIntegrationArn("arn:aws:redshift:" + REGION + ":" + ACCOUNT + ":integration:" + name);
        try {
            publisher.queue(IntegrationEvent.SYNCHRONIZING, integration, REGION, Instant.now());
            publisher.queue(IntegrationEvent.CREATED_ACTIVE, integration, REGION, Instant.now());
            publisher.publishQueued();

            AtomicReference<JsonNode> delivered = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                List<Message> messages = RequestScopes.callAs(ACCOUNT, REGION,
                        () -> sqs.receiveMessage(queue, 1, 30, 0, REGION));
                if (messages.isEmpty()) {
                    return false;
                }
                delivered.set(mapper.readTree(messages.getFirst().getBody()));
                return true;
            });
            assertEquals("aws.redshift", delivered.get().path("source").asText());
            assertEquals("Redshift Integration Monitoring", delivered.get().path("detail-type").asText());
            assertEquals(ACCOUNT, delivered.get().path("account").asText());
            assertEquals(integration.getIntegrationArn(), delivered.get().path("resources").get(0).asText());
            assertEquals("REDSHIFT-INTEGRATION-EVENT-0000", delivered.get().path("detail").path("eventId").asText());
            assertTrue(RequestScopes.callAs(OTHER_ACCOUNT, REGION,
                    () -> sqs.receiveMessage(otherQueue, 1, 30, 0, REGION)).isEmpty());
        } finally {
            deleteRuleAndQueue(ACCOUNT, name, queue);
            deleteRuleAndQueue(OTHER_ACCOUNT, name, otherQueue);
        }
    }

    private String createRuleAndQueue(String account, String name, String pattern) {
        return RequestScopes.callAs(account, REGION, () -> {
            String queue = sqs.createQueue(name, Map.of(), REGION).getQueueUrl();
            events.putRule(name, "default", pattern, null, RuleState.ENABLED, null, null, Map.of(), REGION);
            events.putTargets(name, "default", List.of(new Target("queue",
                    "arn:aws:sqs:" + REGION + ":" + account + ":" + name, null, null)), REGION);
            return queue;
        });
    }

    private void deleteRuleAndQueue(String account, String name, String queue) {
        RequestScopes.runAs(account, REGION, () -> {
            events.removeTargets(name, "default", List.of("queue"), REGION);
            events.deleteRule(name, "default", REGION);
            sqs.deleteQueue(queue, REGION);
        });
    }
}
