package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.RuleState;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EventBridgeManagedRuleTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRINCIPAL = "events.amazonaws.com";
    private static final String RULE = "abcdefghijklmnopqrstuvwxy";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/" + RULE;
    private static final String BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-busv2/orders/zzzzzzzzzzzzzzzzzzzzzzzzz";

    private EventBridgeService service;
    private EventBridgeHandler handler;

    @BeforeEach
    void setUp() {
        service = new EventBridgeService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), MAPPER, null,
                mock(TargetDispatcher.class), mock(ReplayDispatcher.class), new ResourceGroupsTaggingService(null),
                mock(AppSyncTargetValidator.class), null);
        handler = new EventBridgeHandler(service, MAPPER);
        service.putManagedRule(RULE, PRINCIPAL, "{\"source\":[\"aws.ssm\"]}", new Target(RULE, BUS_ARN, null, null),
                REGION);
    }

    @Test
    void describeAndListAnswerManagedBy() throws Exception {
        JsonNode described = call("DescribeRule", "{\"Name\":\"" + RULE + "\"}");
        assertEquals(PRINCIPAL, described.path("ManagedBy").textValue());
        assertEquals("{\"source\":[\"aws.ssm\"]}", described.path("EventPattern").textValue());

        JsonNode listed = call("ListRules", "{}");
        assertEquals(PRINCIPAL, listed.path("Rules").get(0).path("ManagedBy").textValue());
    }

    @Test
    void aRuleThatIsNotManagedAnswersNoManagedBy() throws Exception {
        service.putRule("plain", null, "{\"source\":[\"x\"]}", null, RuleState.ENABLED, null, null, null, REGION);

        JsonNode described = call("DescribeRule", "{\"Name\":\"plain\"}");

        assertTrue(described.path("ManagedBy").isMissingNode());
    }

    @Test
    void theManagedRuleHasOneEnabledTargetOnTheDefaultBus() {
        Rule rule = service.describeRule(RULE, null, REGION);

        assertEquals(RuleState.ENABLED, rule.getState());
        assertEquals("default", rule.getEventBusName());
        List<Target> targets = service.listTargetsByRule(RULE, null, REGION);
        assertEquals(1, targets.size());
        assertEquals(RULE, targets.getFirst().getId());
        assertEquals(BUS_ARN, targets.getFirst().getArn());
    }

    @Test
    void deleteRuleNeedsForce() {
        AwsException e = assertThrows(AwsException.class, () -> service.deleteRule(RULE, null, REGION));

        assertManaged(e, RULE + " is a managed rule. Set 'force' parameter to true to override.");
    }

    @Test
    void removeTargetsNeedsForce() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.removeTargets(RULE, null, List.of(RULE), REGION));

        assertManaged(e, RULE + " is a managed rule. Set 'force' parameter to true to override.");
        assertEquals(1, service.listTargetsByRule(RULE, null, REGION).size());
    }

    @Test
    void disableEnablePutRuleAndPutTargetsRefuseAManagedRule() {
        String message = RULE + " is a managed rule.";
        assertManaged(assertThrows(AwsException.class, () -> service.disableRule(RULE, null, REGION)), message);
        assertManaged(assertThrows(AwsException.class, () -> service.enableRule(RULE, null, REGION)), message);
        assertManaged(assertThrows(AwsException.class, () -> service.putRule(RULE, null, "{\"source\":[\"x\"]}", null,
                RuleState.ENABLED, null, null, null, REGION)), message);
        assertManaged(assertThrows(AwsException.class, () -> service.putTargets(RULE, null,
                List.of(new Target("t", "arn:aws:sqs:us-east-1:000000000000:q", null, null)), REGION)), message);
    }

    @Test
    void tagAndUntagRefuseAManagedRuleButListTagsAnswersNone() {
        String message = "Tag related operations on the managed rule " + RULE + " is not allowed.";
        assertManaged(assertThrows(AwsException.class,
                () -> service.tagResource(RULE_ARN, Map.of("k", "v"), REGION)), message);
        assertManaged(assertThrows(AwsException.class,
                () -> service.untagResource(RULE_ARN, List.of("k"), REGION)), message);

        assertTrue(service.listTagsForResource(RULE_ARN, REGION).isEmpty());
    }

    @Test
    void removeTargetsWithForceThenDeleteRuleWithForceWorks() throws Exception {
        assertEquals(0, call("RemoveTargets", "{\"Rule\":\"" + RULE + "\",\"Ids\":[\"" + RULE + "\"],\"Force\":true}")
                .path("FailedEntryCount").asInt());
        call("DeleteRule", "{\"Name\":\"" + RULE + "\",\"Force\":true}");

        assertTrue(service.listRules(null, null, REGION).isEmpty());
    }

    @Test
    void deleteRuleWithForceStillFailsWhileTheRuleHasTargets() {
        AwsException e = assertThrows(AwsException.class, () -> service.deleteRule(RULE, null, true, REGION));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Rule can't be deleted since it has targets.", e.getMessage());
    }

    @Test
    void deleteRuleWithNoForceInTheRequestIsRefused() throws Exception {
        try (Response response = handler.handle("DeleteRule", MAPPER.readTree("{\"Name\":\"" + RULE + "\"}"),
                REGION)) {
            assertEquals(400, response.getStatus());
            AwsErrorResponse error = (AwsErrorResponse) response.getEntity();
            assertEquals("ManagedRuleException", error.type());
        }
    }

    @Test
    void listRuleNamesByTargetFindsTheManagedRuleByTheBusArn() {
        assertEquals(List.of(RULE), service.listRuleNamesByTarget(BUS_ARN, null, null, null, REGION).items());
    }

    @Test
    void deleteManagedRuleRemovesTheRuleAndItsTargetsAndToleratesAMissingRule() {
        service.deleteManagedRule(RULE, REGION);

        assertTrue(service.listRules(null, null, REGION).isEmpty());
        assertDoesNotThrow(() -> service.deleteManagedRule(RULE, REGION));
    }

    @Test
    void putManagedRuleRewritesTheRuleAndReplacesTheTarget() {
        Target target = new Target(RULE, BUS_ARN, null, null);
        target.setDeadLetterConfig(new Target.DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dead"));

        service.putManagedRule(RULE, PRINCIPAL, "{\"source\":[\"aws.s3\"]}", target, REGION);

        assertEquals("{\"source\":[\"aws.s3\"]}", service.describeRule(RULE, null, REGION).getEventPattern());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:dead",
                service.listTargetsByRule(RULE, null, REGION).getFirst().getDeadLetterConfig().arn());
        service.putManagedRule(RULE, PRINCIPAL, "{\"source\":[\"aws.s3\"]}", new Target(RULE, BUS_ARN, null, null),
                REGION);
        assertNull(service.listTargetsByRule(RULE, null, REGION).getFirst().getDeadLetterConfig());
    }

    private JsonNode call(String action, String body) throws Exception {
        try (Response response = handler.handle(action, MAPPER.readTree(body), REGION)) {
            assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
            return (JsonNode) response.getEntity();
        }
    }

    private static void assertManaged(AwsException e, String message) {
        assertEquals("ManagedRuleException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }
}
