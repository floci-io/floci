package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDataEventsIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    RedshiftDataService service;
    @Inject
    EventBridgeService events;
    @Inject
    SqsService sqs;
    @Inject
    ObjectMapper mapper;
    @InjectMock
    RedshiftDataResourceResolver resolver;
    @InjectMock
    RedshiftDataConnectionFactory factory;

    @Test
    void statusEventReachesOnlyTheCapturedAccountRule() throws Exception {
        String name = "rsdata-event-" + UUID.randomUUID();
        String pattern = mapper.writeValueAsString(Map.of("source", List.of("aws.redshift-data"),
                "detail", Map.of("statementName", List.of(name))));
        String queue = createRuleAndQueue(ACCOUNT, name, pattern);
        String otherQueue = createRuleAndQueue(OTHER_ACCOUNT, name, pattern);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:" + ACCOUNT + ":cluster:analytics",
                "localhost", 5439, "dev", "admin", "password"));
        when(factory.open(any())).thenThrow(new SQLException("connection refused"));
        try {
            String id = RequestScopes.callAs(ACCOUNT, REGION, () -> service.executeStatement(
                    mapper.createObjectNode().put("Sql", "SELECT 1").put("WithEvent", true)
                            .put("StatementName", name), REGION, "arn:aws:iam::" + ACCOUNT + ":role/etl"))
                    .path("Id").asText();
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
            assertEquals(ACCOUNT, delivered.get().path("account").asText());
            assertEquals(REGION, delivered.get().path("region").asText());
            assertEquals(id, delivered.get().path("detail").path("statementId").asText());
            assertEquals("FAILED", delivered.get().path("detail").path("state").asText());
            assertEquals("arn:aws:iam::" + ACCOUNT + ":role/etl",
                    delivered.get().path("detail").path("principal").asText());
            assertEquals("FAILED", RequestScopes.callAs(ACCOUNT, REGION, () ->
                    service.describeStatement(mapper.createObjectNode().put("Id", id))).path("Status").asText());
            assertTrue(RequestScopes.callAs(OTHER_ACCOUNT, REGION,
                    () -> sqs.receiveMessage(otherQueue, 1, 30, 0, REGION)).isEmpty());
        } finally {
            deleteRuleAndQueue(ACCOUNT, name, queue);
            deleteRuleAndQueue(OTHER_ACCOUNT, name, otherQueue);
            service.clear();
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
