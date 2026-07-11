package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventBridgeServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EventBridgeService service;
    private EventBridgeInvoker invokerMock;

    @BeforeEach
    void setUp() {
        invokerMock = mock(EventBridgeInvoker.class);
        service = new EventBridgeService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                new ObjectMapper(),
                null,
                invokerMock,
                null,
                new ResourceGroupsTaggingService(null)
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
    void matchesPatternByResources() {
     Map<String, Object> event = Map.of(
        "Source", "my.app",
        "Detail", "Payload",
        "Resources", OBJECT_MAPPER.createArrayNode().add("resource1").add("resource2")
     );

     assertTrue(service.matchesPattern(event, "{\"resources\":[\"resource1\"]}"));
     assertTrue(service.matchesPattern(event, "{\"resources\":[\"resource2\"]}"));
     assertTrue(service.matchesPattern(event, "{\"resources\":[\"resource1\",\"resource2\"]}"));
     assertFalse(service.matchesPattern(event, "{\"resources\":[\"resource3\"]}"));
     assertFalse(service.matchesPattern(event, "{\"resources\":[\"*\"]}"));
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

    @Test
    void putEventsShouldInvokeLambdaTarget() {
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                "A test rule", null, null, REGION);
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:lambda:us-east-1:000000000000:function:my-function");
        service.putTargets("my-rule", null, List.of(target), "us-east-1");

        ArrayNode resources = OBJECT_MAPPER.createArrayNode().add("resource1");
        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}", "Resources", resources)
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, REGION);
        assertEquals(0, result.failedCount());
        assertEquals(1, result.entries().size());
        assertNotNull(result.entries().getFirst().get("EventId"));
        verify(invokerMock).invokeTarget(eq(target), any(String.class), eq(REGION));
    }

    @Test
    void putEventsShouldInvokeSqsTarget() {
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                "A test rule", null, null, REGION);
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:us-east-1:000000000000:my-queue");
        service.putTargets("my-rule", null, List.of(target), "us-east-1");

        ArrayNode resources = OBJECT_MAPPER.createArrayNode().add("resource1");
        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}", "Resources", resources)
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, REGION);
        assertEquals(0, result.failedCount());
        assertEquals(1, result.entries().size());
        assertNotNull(result.entries().getFirst().get("EventId"));
        verify(invokerMock).invokeTarget(eq(target), any(String.class), eq(REGION));
    }

    @Test
    void putEventsShouldInvokeSnsTarget() {
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                "A test rule", null, null, REGION);
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sns:us-east-1:000000000000:my-topic");
        service.putTargets("my-rule", null, List.of(target), "us-east-1");

        ArrayNode resources = OBJECT_MAPPER.createArrayNode().add("resource1");
        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}", "Resources", resources)
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, REGION);
        assertEquals(0, result.failedCount());
        assertEquals(1, result.entries().size());
        assertNotNull(result.entries().getFirst().get("EventId"));
        verify(invokerMock).invokeTarget(eq(target), any(String.class), eq(REGION));
    }

    @Test
    void matchesPatternBySourcePrefix_matches() {
        Map<String, Object> event = Map.of("Source", "com.example.myapp", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, "{\"source\":[{\"prefix\":\"com.example\"}]}"));
    }

    @Test
    void matchesPatternBySourcePrefix_noMatch() {
        Map<String, Object> event = Map.of("Source", "org.example.myapp", "DetailType", "Order");
        assertFalse(service.matchesPattern(event, "{\"source\":[{\"prefix\":\"com.example\"}]}"));
    }

    @Test
    void matchesPatternBySuffix_matches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "order.json");
        assertTrue(service.matchesPattern(event, "{\"detail-type\":[{\"suffix\":\".json\"}]}"));
    }

    @Test
    void matchesPatternBySuffix_noMatch() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "order.xml");
        assertFalse(service.matchesPattern(event, "{\"detail-type\":[{\"suffix\":\".json\"}]}"));
    }

    @Test
    void matchesPatternByEqualsIgnoreCase_matches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "PROD");
        assertTrue(service.matchesPattern(event, "{\"detail-type\":[{\"equals-ignore-case\":\"prod\"}]}"));
    }

    @Test
    void matchesPatternByEqualsIgnoreCase_noMatch() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "PROD");
        assertFalse(service.matchesPattern(event, "{\"detail-type\":[{\"equals-ignore-case\":\"dev\"}]}"));
    }

    @Test
    void matchesPatternByAnythingBut_matches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, "{\"detail-type\":[{\"anything-but\":[\"Payment\"]}]}"));
    }

    @Test
    void matchesPatternByAnythingBut_noMatch() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Payment");
        assertFalse(service.matchesPattern(event, "{\"detail-type\":[{\"anything-but\":[\"Payment\"]}]}"));
    }

    @Test
    void matchesPatternByAnythingButPrefix_matches() {
        Map<String, Object> event = Map.of("Source", "com.example.app", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, "{\"source\":[{\"anything-but\":{\"prefix\":\"aws.\"}}]}"));
    }

    @Test
    void matchesPatternByAnythingButPrefix_noMatch() {
        Map<String, Object> event = Map.of("Source", "aws.events", "DetailType", "Order");
        assertFalse(service.matchesPattern(event, "{\"source\":[{\"anything-but\":{\"prefix\":\"aws.\"}}]}"));
    }

    @Test
    void matchesPatternByDetailPrefixField_matches() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CONFIRMED_BY_USER\"}"
        );
        assertTrue(service.matchesPattern(event, "{\"detail\":{\"status\":[{\"prefix\":\"CONFIRMED\"}]}}"));
    }

    @Test
    void matchesPatternByExists_matches() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CONFIRMED\"}"
        );
        assertTrue(service.matchesPattern(event, "{\"detail\":{\"status\":[{\"exists\":true}]}}"));
        assertTrue(service.matchesPattern(event, "{\"detail\":{\"other\":[{\"exists\":false}]}}"));
    }

    @Test
    void matchesPatternByAccount_matches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, "{\"account\":[\"000000000000\"]}"));
    }

    @Test
    void matchesPatternByAccount_noMatch() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertFalse(service.matchesPattern(event, "{\"account\":[\"999999999999\"]}"));
    }

    @Test
    void matchesPatternByRegion_matches() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertTrue(service.matchesPattern(event, "{\"region\":[\"us-east-1\"]}"));
    }

    @Test
    void matchesPatternByRegion_noMatch() {
        Map<String, Object> event = Map.of("Source", "my.app", "DetailType", "Order");
        assertFalse(service.matchesPattern(event, "{\"region\":[\"eu-west-1\"]}"));
    }

    @Test
    void matchesPatternByNestedDetail_matches() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"object\":{\"path\":\"uploads/image.png\",\"size\":1024}}"
        );
        assertTrue(service.matchesPattern(event,
                "{\"detail\":{\"object\":{\"path\":[{\"prefix\":\"uploads/\"}]}}}"));
    }

    @Test
    void matchesPatternByNestedDetail_noMatch() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"object\":{\"path\":\"downloads/file.txt\",\"size\":1024}}"
        );
        assertFalse(service.matchesPattern(event,
                "{\"detail\":{\"object\":{\"path\":[{\"prefix\":\"uploads/\"}]}}}"));
    }

    @Test
    void matchesPatternByDeeplyNestedDetail() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"a\":{\"b\":{\"c\":\"deep-value\"}}}"
        );
        assertTrue(service.matchesPattern(event,
                "{\"detail\":{\"a\":{\"b\":{\"c\":[\"deep-value\"]}}}}"));
        assertFalse(service.matchesPattern(event,
                "{\"detail\":{\"a\":{\"b\":{\"c\":[\"wrong\"]}}}}"));
    }

    @Test
    void matchesPatternTopLevelOr_firstBranchMatches() {
        Map<String, Object> event = Map.of("Source", "aws.ec2", "DetailType", "Other");
        assertTrue(service.matchesPattern(event,
                "{\"$or\":[{\"source\":[\"aws.ec2\"]},{\"source\":[\"aws.s3\"]}]}"));
    }

    @Test
    void matchesPatternTopLevelOr_secondBranchMatches() {
        Map<String, Object> event = Map.of("Source", "aws.s3", "DetailType", "Other");
        assertTrue(service.matchesPattern(event,
                "{\"$or\":[{\"source\":[\"aws.ec2\"]},{\"source\":[\"aws.s3\"]}]}"));
    }

    @Test
    void matchesPatternTopLevelOr_noBranchMatches() {
        Map<String, Object> event = Map.of("Source", "aws.rds", "DetailType", "Other");
        assertFalse(service.matchesPattern(event,
                "{\"$or\":[{\"source\":[\"aws.ec2\"]},{\"source\":[\"aws.s3\"]}]}"));
    }

    @Test
    void matchesPatternTopLevelOrCombinedWithOtherField() {
        Map<String, Object> event = Map.of("Source", "aws.ec2", "DetailType", "EC2 Instance State-change Notification");
        // source must match AND one of the detail-types must match
        assertTrue(service.matchesPattern(event,
                "{\"source\":[\"aws.ec2\"],\"$or\":[{\"detail-type\":[\"EC2 Instance State-change Notification\"]},{\"detail-type\":[\"EC2 Spot Instance Interruption Warning\"]}]}"));
        // source matches but neither detail-type matches
        assertFalse(service.matchesPattern(event,
                "{\"source\":[\"aws.ec2\"],\"$or\":[{\"detail-type\":[\"Something Else\"]},{\"detail-type\":[\"Also Wrong\"]}]}"));
    }

    @Test
    void matchesPatternDetailLevelOr_matches() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CONFIRMED\"}"
        );
        assertTrue(service.matchesPattern(event,
                "{\"detail\":{\"$or\":[{\"status\":[\"CONFIRMED\"]},{\"status\":[\"PENDING\"]}]}}"));
    }

    @Test
    void matchesPatternDetailLevelOr_noMatch() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CANCELLED\"}"
        );
        assertFalse(service.matchesPattern(event,
                "{\"detail\":{\"$or\":[{\"status\":[\"CONFIRMED\"]},{\"status\":[\"PENDING\"]}]}}"));
    }

    @Test
    void matchesPatternDetailLevelOrCombinedWithOtherField() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "Detail", "{\"status\":\"CONFIRMED\",\"region\":\"us-east-1\"}"
        );
        // region matches AND one of the statuses matches
        assertTrue(service.matchesPattern(event,
                "{\"detail\":{\"region\":[\"us-east-1\"],\"$or\":[{\"status\":[\"CONFIRMED\"]},{\"status\":[\"PENDING\"]}]}}"));
        // region matches but status doesn't
        assertFalse(service.matchesPattern(event,
                "{\"detail\":{\"region\":[\"us-east-1\"],\"$or\":[{\"status\":[\"CANCELLED\"]},{\"status\":[\"FAILED\"]}]}}"));
    }

    @Test
    void matchesPatternCombinesAccountRegionAndDetail() {
        Map<String, Object> event = Map.of(
                "Source", "my.app",
                "DetailType", "Order",
                "Detail", "{\"status\":\"CONFIRMED\"}"
        );
        assertTrue(service.matchesPattern(event,
                "{\"source\":[\"my.app\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"],\"detail\":{\"status\":[\"CONFIRMED\"]}}"));
        assertFalse(service.matchesPattern(event,
                "{\"source\":[\"my.app\"],\"account\":[\"999999999999\"],\"detail\":{\"status\":[\"CONFIRMED\"]}}"));
    }

    // ─────────────── Envelope region/account propagation ───────────────

    @Test
    void putEvents_envelopeRegionMatchesPutEventsCallRegion() throws Exception {
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                "A test rule", null, null, "eu-west-1");
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:eu-west-1:000000000000:my-queue");
        service.putTargets("my-rule", null, List.of(target), "eu-west-1");

        List<Map<String, Object>> entries = List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")
        );

        EventBridgeService.PutEventsResult result = service.putEvents(entries, "eu-west-1");
        assertEquals(0, result.failedCount());

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(invokerMock).invokeTarget(eq(target), json.capture(), eq("eu-west-1"));
        com.fasterxml.jackson.databind.JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
        assertEquals("eu-west-1", envelope.path("region").asText(),
                "envelope.region should reflect the PutEvents call's region, not the resolver default");
        assertEquals("000000000000", envelope.path("account").asText());
    }

    @Test
    void putEvents_envelopeRegionFromEntryWinsOverCallRegion() throws Exception {
        // Simulates the archive-replay path which stamps "Region" / "Account" on the
        // re-emitted entry from the originating event's envelope.
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, "us-west-2");
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:us-west-2:000000000000:replay-queue");
        service.putTargets("my-rule", null, List.of(target), "us-west-2");

        java.util.Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("Source", "my.app");
        entry.put("DetailType", "Test");
        entry.put("Detail", "{}");
        entry.put("Region", "ap-northeast-1");
        entry.put("Account", "111111111111");

        service.putEvents(List.of(entry), "us-west-2");

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(invokerMock).invokeTarget(eq(target), json.capture(), eq("us-west-2"));
        com.fasterxml.jackson.databind.JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
        assertEquals("ap-northeast-1", envelope.path("region").asText(),
                "entry.Region should win over the PutEvents call region");
        assertEquals("111111111111", envelope.path("account").asText(),
                "entry.Account should win over the resolver default");
    }

    @Test
    void putEvents_matchesPatternUsesCallRegionForRegionFilter() throws Exception {
        // A rule with a region-filter for the call region (eu-west-1) must match a
        // PutEvents entry that didn't supply Region — the entry should be normalized to
        // the call region before matchesPattern() sees it. Without the normalization the
        // pattern's region filter would compare against the resolver default (us-east-1)
        // and the rule would miss.
        service.putRule("region-filtered", null,
                "{\"source\":[\"my.app\"],\"region\":[\"eu-west-1\"]}",
                null, RuleState.ENABLED, null, null, null, "eu-west-1");
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:eu-west-1:000000000000:filtered-queue");
        service.putTargets("region-filtered", null, List.of(target), "eu-west-1");

        service.putEvents(List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")), "eu-west-1");

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(invokerMock).invokeTarget(eq(target), json.capture(), eq("eu-west-1"));
        com.fasterxml.jackson.databind.JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
        assertEquals("eu-west-1", envelope.path("region").asText(),
                "envelope and pattern matching must agree on the entry's effective region");
    }

    @Test
    void putEvents_matchesPatternRejectsWrongRegionFilterUnderNonDefaultCall() {
        // The mirror of the above: a rule whose region-filter does NOT match the call
        // region must not fire. Pre-fix, an entry without Region would default to
        // resolver-default us-east-1 in matchesPattern, accidentally satisfying a filter
        // like {"region":["us-east-1"]} even when PutEvents was called against
        // eu-west-1.
        service.putRule("default-region-filtered", null,
                "{\"source\":[\"my.app\"],\"region\":[\"us-east-1\"]}",
                null, RuleState.ENABLED, null, null, null, "eu-west-1");
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:eu-west-1:000000000000:wrong-region-queue");
        service.putTargets("default-region-filtered", null, List.of(target), "eu-west-1");

        service.putEvents(List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")), "eu-west-1");

        verify(invokerMock, org.mockito.Mockito.never()).invokeTarget(eq(target), any(), any());
    }

    @Test
    void putEvents_envelopeDefaultsRegressionForDefaultRegionCall() throws Exception {
        // Regression: when neither the entry nor the call carries a region (callers in
        // the default region), the envelope still stamps the resolver default — same
        // behavior as before the fix.
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, REGION);
        Target target = new Target();
        target.setId("t1");
        target.setArn("arn:aws:sqs:us-east-1:000000000000:default-queue");
        service.putTargets("my-rule", null, List.of(target), REGION);

        service.putEvents(List.of(
                Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")), REGION);

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(invokerMock).invokeTarget(eq(target), json.capture(), eq(REGION));
        com.fasterxml.jackson.databind.JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
        assertEquals(REGION, envelope.path("region").asText());
        assertEquals("000000000000", envelope.path("account").asText());
    }
}
