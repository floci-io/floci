package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventBridgeServiceTest {

    private static final String REGION = "us-east-1";

    private EventBridgeService service;

    @BeforeEach
    void setUp() {
        service = new EventBridgeService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                null, null, null,
                new ObjectMapper()
        );
    }

    // ──────────────────────────── Event Buses ────────────────────────────

    @Test
    void getOrCreateDefaultBus() {
        EventBus bus = service.getOrCreateDefaultBus(REGION);
        assertEquals("default", bus.getName());
        assertNotNull(bus.getArn());
    }

    @Test
    void createEventBus() {
        EventBus bus = service.createEventBus("my-bus", "A custom bus", null, REGION);
        assertEquals("my-bus", bus.getName());
        assertTrue(bus.getArn().contains("my-bus"));
    }

    @Test
    void createEventBusDuplicateThrows() {
        service.createEventBus("my-bus", null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.createEventBus("my-bus", null, null, REGION));
    }

    @Test
    void createEventBusBlankNameThrows() {
        assertThrows(AwsException.class, () ->
                service.createEventBus("", null, null, REGION));
    }

    @Test
    void deleteEventBus() {
        service.createEventBus("my-bus", null, null, REGION);
        service.deleteEventBus("my-bus", REGION);

        assertThrows(AwsException.class, () ->
                service.describeEventBus("my-bus", REGION));
    }

    @Test
    void deleteDefaultBusThrows() {
        assertThrows(AwsException.class, () ->
                service.deleteEventBus("default", REGION));
    }

    @Test
    void deleteEventBusWithRulesThrows() {
        service.createEventBus("my-bus", null, null, REGION);
        service.putRule("rule-1", "my-bus", null, "rate(1 minute)", RuleState.ENABLED, null, null, null, REGION);

        assertThrows(AwsException.class, () ->
                service.deleteEventBus("my-bus", REGION));
    }

    @Test
    void listEventBuses() {
        service.createEventBus("bus-a", null, null, REGION);
        service.createEventBus("bus-b", null, null, REGION);

        List<EventBus> buses = service.listEventBuses(null, REGION);
        // default + bus-a + bus-b
        assertEquals(3, buses.size());
    }

    @Test
    void listEventBusesWithPrefix() {
        service.createEventBus("prod-orders", null, null, REGION);
        service.createEventBus("prod-payments", null, null, REGION);
        service.createEventBus("dev-orders", null, null, REGION);

        List<EventBus> result = service.listEventBuses("prod-", REGION);
        assertEquals(2, result.size());
    }

    // ──────────────────────────── Rules ────────────────────────────

    @Test
    void putRule() {
        Rule rule = service.putRule("my-rule", null,
                "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                "A test rule", null, null, REGION);

        assertEquals("my-rule", rule.getName());
        assertEquals(RuleState.ENABLED, rule.getState());
        assertNotNull(rule.getArn());
    }

    @Test
    void putRuleIsIdempotent() {
        service.putRule("my-rule", null, null, "rate(5 minutes)", RuleState.ENABLED,
                null, null, null, REGION);
        service.putRule("my-rule", null, null, "rate(10 minutes)", RuleState.ENABLED,
                null, null, null, REGION);

        List<Rule> rules = service.listRules(null, null, REGION);
        assertEquals(1, rules.size());
        assertEquals("rate(10 minutes)", rules.getFirst().getScheduleExpression());
    }

    @Test
    void putRuleForNonExistentBusThrows() {
        assertThrows(AwsException.class, () ->
                service.putRule("rule", "missing-bus", null, null, null, null, null, null, REGION));
    }

    @Test
    void deleteRule() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);
        service.deleteRule("my-rule", null, REGION);

        assertTrue(service.listRules(null, null, REGION).isEmpty());
    }

    @Test
    void deleteRuleWithTargetsThrows() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:us-east-1:000000000000:my-queue");
        service.putTargets("my-rule", null, List.of(target), REGION);

        assertThrows(AwsException.class, () ->
                service.deleteRule("my-rule", null, REGION));
    }

    @Test
    void enableAndDisableRule() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.DISABLED,
                null, null, null, REGION);

        service.enableRule("my-rule", null, REGION);
        assertEquals(RuleState.ENABLED, service.describeRule("my-rule", null, REGION).getState());

        service.disableRule("my-rule", null, REGION);
        assertEquals(RuleState.DISABLED, service.describeRule("my-rule", null, REGION).getState());
    }

    @Test
    void listRulesWithPrefix() {
        service.putRule("prod-rule-1", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);
        service.putRule("prod-rule-2", null, null, "rate(5 minutes)", RuleState.ENABLED,
                null, null, null, REGION);
        service.putRule("dev-rule-1", null, null, "rate(1 hour)", RuleState.ENABLED,
                null, null, null, REGION);

        List<Rule> result = service.listRules(null, "prod-", REGION);
        assertEquals(2, result.size());
    }

    // ──────────────────────────── Targets ────────────────────────────

    @Test
    void putAndListTargets() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);

        Target t1 = new Target();
        t1.setId("target-1");
        t1.setArn("arn:aws:sqs:us-east-1:000000000000:queue-1");

        Target t2 = new Target();
        t2.setId("target-2");
        t2.setArn("arn:aws:sqs:us-east-1:000000000000:queue-2");

        service.putTargets("my-rule", null, List.of(t1, t2), REGION);

        List<Target> targets = service.listTargetsByRule("my-rule", null, REGION);
        assertEquals(2, targets.size());
    }

    @Test
    void putTargetsIsIdempotent() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);

        Target t = new Target();
        t.setId("t1");
        t.setArn("arn:aws:sqs:us-east-1:000000000000:queue");

        service.putTargets("my-rule", null, List.of(t), REGION);
        t.setArn("arn:aws:sqs:us-east-1:000000000000:queue-updated");
        service.putTargets("my-rule", null, List.of(t), REGION);

        List<Target> targets = service.listTargetsByRule("my-rule", null, REGION);
        assertEquals(1, targets.size());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:queue-updated", targets.getFirst().getArn());
    }

    @Test
    void removeTargets() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);

        Target t1 = new Target();
        t1.setId("t1");
        t1.setArn("arn:aws:sqs:us-east-1:000000000000:queue-1");
        Target t2 = new Target();
        t2.setId("t2");
        t2.setArn("arn:aws:sqs:us-east-1:000000000000:queue-2");

        service.putTargets("my-rule", null, List.of(t1, t2), REGION);
        EventBridgeService.RemoveTargetsResult result = service.removeTargets(
                "my-rule", null, List.of("t1"), REGION);

        assertEquals(1, result.successfulCount());
        assertEquals(0, result.failedCount());
        assertEquals(1, service.listTargetsByRule("my-rule", null, REGION).size());
    }

    // ──────────────────────────── Pattern Matching ────────────────────────────

    @Test
    void matchesPatternNullPatternAlwaysMatches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, null));
        assertTrue(service.matchesPattern(event, ""));
    }

    @Test
    void matchesPatternBySource() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");

        assertTrue(service.matchesPattern(event, "{\"source\":[\"my.app\"]}"));
        assertFalse(service.matchesPattern(event, "{\"source\":[\"other.app\"]}"));
    }

    @Test
    void matchesPatternByDetailType() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "OrderCreated");

        assertTrue(service.matchesPattern(event, "{\"detail-type\":[\"OrderCreated\"]}"));
        assertFalse(service.matchesPattern(event, "{\"detail-type\":[\"OrderDeleted\"]}"));
    }

    @Test
    void matchesPatternBySourceAndDetailType() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "OrderCreated");

        assertTrue(service.matchesPattern(event,
                "{\"source\":[\"my.app\"],\"detail-type\":[\"OrderCreated\"]}"));
        assertFalse(service.matchesPattern(event,
                "{\"source\":[\"my.app\"],\"detail-type\":[\"OrderDeleted\"]}"));
    }

    @Test
    void matchesPatternByDetail() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CONFIRMED\",\"amount\":\"100\"}"
        );

        assertTrue(service.matchesPattern(event, "{\"detail\":{\"status\":[\"CONFIRMED\"]}}"));
        assertFalse(service.matchesPattern(event, "{\"detail\":{\"status\":[\"PENDING\"]}}"));
    }

    @Test
    void putEventsReturnsEventIds() {
        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, REGION);

        assertEquals(0, result.failedCount());
        assertEquals(1, result.entries().size());
        assertNotNull(result.entries().getFirst().get("EventId"));
    }

    @Test
    void putEventsFailsForNonExistentBus() {
        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test",
                        "Detail", "{}", "EventBusName", "non-existent-bus")
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, REGION);
        assertEquals(1, result.failedCount());
    }

    // ──────────────────────────── Cross-Region SQS Delivery ────────────────────────────

    @Test
    void putEventsDeliversToCrossRegionSqsTarget() {
        String crossRegion = "eu-west-1";
        String queueName = "cross-region-queue";
        String sqsArn = "arn:aws:sqs:" + crossRegion + ":000000000000:" + queueName;

        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        SqsService sqsService = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, "http://localhost:4566", regionResolver);

        sqsService.createQueue(queueName, null, crossRegion);

        EventBridgeService ebWithSqs = new EventBridgeService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, null, sqsService, null, new ObjectMapper());

        ebWithSqs.putRule("test-rule", null,
                "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, REGION);

        Target target = new Target();
        target.setId("sqs-target");
        target.setArn(sqsArn);
        ebWithSqs.putTargets("test-rule", null, List.of(target), REGION);

        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{\"key\":\"value\"}")
        );
        EventBridgeService.PutEventsResult result = ebWithSqs.putEvents(entries, REGION);
        assertEquals(0, result.failedCount());

        String queueUrl = "http://localhost:4566/000000000000/" + queueName;
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, crossRegion);
        assertEquals(1, messages.size());
    }

    @Test
    void putEventsDeliversToSameRegionSqsTarget() {
        String queueName = "same-region-queue";
        String sqsArn = "arn:aws:sqs:" + REGION + ":000000000000:" + queueName;

        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        SqsService sqsService = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, "http://localhost:4566", regionResolver);

        sqsService.createQueue(queueName, null, REGION);

        EventBridgeService ebWithSqs = new EventBridgeService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, null, sqsService, null, new ObjectMapper());

        ebWithSqs.putRule("test-rule", null,
                "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, REGION);

        Target target = new Target();
        target.setId("sqs-target");
        target.setArn(sqsArn);
        ebWithSqs.putTargets("test-rule", null, List.of(target), REGION);

        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")
        );
        EventBridgeService.PutEventsResult result = ebWithSqs.putEvents(entries, REGION);
        assertEquals(0, result.failedCount());

        String queueUrl = "http://localhost:4566/000000000000/" + queueName;
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
    }
}