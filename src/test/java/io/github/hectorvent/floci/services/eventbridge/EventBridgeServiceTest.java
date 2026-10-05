package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.eventbridge.model.ApiDestination;
import io.github.hectorvent.floci.services.eventbridge.model.ApiDestinationState;
import io.github.hectorvent.floci.services.eventbridge.model.Archive;
import io.github.hectorvent.floci.services.eventbridge.model.Connection;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Replay;
import io.github.hectorvent.floci.services.eventbridge.model.ReplayState;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class EventBridgeServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_BUS = "arn:aws:events:us-east-1:000000000000:event-bus/default";

    private EventBridgeService service;
    private TargetDispatcher dispatcherMock;
    private StorageBackend<String, Replay> replayStore;
    private ReplayDispatcher replayDispatcherMock;

    @BeforeEach
    void setUp() {
        dispatcherMock = mock(TargetDispatcher.class);
        replayStore = new InMemoryStorage<>();
        replayDispatcherMock = mock(ReplayDispatcher.class);
        service = new EventBridgeService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                replayStore,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                new ObjectMapper(),
                null,
                dispatcherMock,
                replayDispatcherMock,
                new ResourceGroupsTaggingService(null)
        );
    }

    @Test
    void cancelReplayForwardsTheStoredReplayArn() {
        Replay replay = new Replay();
        replay.setReplayName("shared");
        replay.setReplayArn("arn:aws:events:us-east-1:111111111111:replay/shared");
        replay.setState(ReplayState.RUNNING);
        replayStore.put("replay:" + REGION + ":shared", replay);
        when(replayDispatcherMock.requestCancel(replay.getReplayArn())).thenReturn(true);

        Replay cancelled = service.cancelReplay("shared", REGION);

        assertEquals(ReplayState.CANCELLING, cancelled.getState());
        verify(replayDispatcherMock).requestCancel(replay.getReplayArn());
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

    @ParameterizedTest
    @ValueSource(strings = {"default", "contains/slash", "contains space", "contains*star"})
    void createEventBusRejectsInvalidCustomNames(String name) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.createEventBus(name, null, null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createEventBusRejectsLongNameAndDescription() {
        assertThrows(AwsException.class, () ->
                service.createEventBus("n".repeat(257), null, null, REGION));
        assertThrows(AwsException.class, () ->
                service.createEventBus("valid-name", "d".repeat(513), null, REGION));
    }

    @Test
    void updateEventBusRejectsLongDescription() {
        service.createEventBus("my-bus", null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.updateEventBus(
                        "my-bus", "d".repeat(513), null, null, null, REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "contains space", "contains*star"})
    void updateEventBusRejectsInvalidExplicitNames(String name) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.updateEventBus(name, "description", null, null, null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void updateEventBusRejectsLongExplicitName() {
        AwsException error = assertThrows(AwsException.class, () ->
                service.updateEventBus(
                        "n".repeat(257), "description", null, null, null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void deleteEventBus() {
        service.createEventBus("my-bus", null, null, REGION);
        service.deleteEventBus("my-bus", REGION);
        assertDoesNotThrow(() -> service.deleteEventBus("my-bus", REGION));

        assertThrows(AwsException.class, () ->
                service.describeEventBus("my-bus", REGION));
    }

    @Test
    void deleteDefaultBusThrows() {
        assertThrows(AwsException.class, () ->
                service.deleteEventBus("default", REGION));
    }

    @Test
    void deleteMissingEventBusIsIdempotent() {
        assertDoesNotThrow(() -> service.deleteEventBus("missing-bus", REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contains space", "contains*star"})
    void deleteEventBusRejectsInvalidNames(String name) {
        AwsException error = assertThrows(
                AwsException.class, () -> service.deleteEventBus(name, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void deleteEventBusRejectsLongName() {
        AwsException error = assertThrows(
                AwsException.class, () -> service.deleteEventBus("n".repeat(257), REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
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

    // ──────────────────────────── Archives ────────────────────────────

    @Test
    void createArchiveAcceptsTheDefaultBusAndAnExistingCustomBus() {
        String custom = service.createEventBus("orders", null, null, REGION).getArn();

        assertEquals("arn:aws:events:us-east-1:000000000000:event-bus/default", service.createArchive(
                "on-default", "arn:aws:events:us-east-1:000000000000:event-bus/default", null, null, 0, REGION)
                .getEventSourceArn());
        assertEquals(custom, service.createArchive("on-custom", custom, null, null, 0, REGION)
                .getEventSourceArn());
    }

    /** The messages AWS returns; the account is checked before the region. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "arn:aws:events:us-east-1:000000000000:event-bus/missing|ResourceNotFoundException|"
                    + "Event bus missing does not exist.",
            "arn:aws:events:eu-west-1:000000000000:event-bus/default|ValidationException|"
                    + "Parameter EventSourceArn is not valid. Reason: Creating cross-region archive is not permitted.",
            "arn:aws:events:us-east-1:111111111111:event-bus/default|AccessDeniedException|"
                    + "Archive event source arn:aws:events:us-east-1:111111111111:event-bus/default "
                    + "does not belong to account 000000000000.",
            "arn:aws:events:eu-west-1:111111111111:event-bus/missing|AccessDeniedException|"
                    + "Archive event source arn:aws:events:eu-west-1:111111111111:event-bus/missing "
                    + "does not belong to account 000000000000."
    })
    void createArchiveRejectsASourceThatIsNotAnExistingBusOfThisAccountAndRegion(
            String sourceArn, String code, String message) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.createArchive("orders-archive", sourceArn, null, null, 0, REGION));

        assertEquals(code, error.getErrorCode());
        assertEquals(message, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.listArchives(null, null, null, REGION).isEmpty());
    }

    /** One out-of-range field each, with the message AWS returns for it. */
    static Stream<Arguments> outOfRangeArchiveFields() {
        String description = "d".repeat(513);
        String pattern = patternOfLength(4097);
        return Stream.of(
                Arguments.of(null, null, -1, "1 validation error detected: Value '-1' at 'retentionDays' "
                        + "failed to satisfy constraint: Member must have value greater than or equal to 0"),
                Arguments.of(description, null, 0, "1 validation error detected: Value '" + description
                        + "' at 'description' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 512"),
                Arguments.of(null, pattern, 0, "1 validation error detected: Value '" + pattern
                        + "' at 'eventPattern' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 4096"));
    }

    @ParameterizedTest
    @MethodSource("outOfRangeArchiveFields")
    void createArchiveRejectsAnOutOfRangeField(String description, String pattern, int retention, String message) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.createArchive("orders-archive", DEFAULT_BUS, description, pattern, retention, REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(message, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.listArchives(null, null, null, REGION).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("outOfRangeArchiveFields")
    void updateArchiveRejectsAnOutOfRangeFieldBeforeLookingTheArchiveUp(
            String description, String pattern, int retention, String message) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.updateArchive("missing-archive", description, pattern, retention, REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(message, error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createArchiveReportsEveryViolationInAwsOrderBeforeCheckingTheSource() {
        String description = "d".repeat(513);
        String pattern = patternOfLength(4097);

        AwsException error = assertThrows(AwsException.class, () -> service.createArchive("orders-archive",
                "arn:aws:events:us-east-1:000000000000:event-bus/missing", description, pattern, -1, REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("3 validation errors detected: "
                + "Value '-1' at 'retentionDays' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0; "
                + "Value '" + description + "' at 'description' failed to satisfy constraint: "
                + "Member must have length less than or equal to 512; "
                + "Value '" + pattern + "' at 'eventPattern' failed to satisfy constraint: "
                + "Member must have length less than or equal to 4096", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void archiveFieldsAtTheirLimitsAreAccepted() {
        String description = "d".repeat(512);
        String pattern = patternOfLength(4096);

        service.createArchive("orders-archive", DEFAULT_BUS, description, pattern, 0, REGION);
        service.updateArchive("orders-archive", description, pattern, 0, REGION);

        Archive archive = service.describeArchive("orders-archive", REGION);
        assertEquals(description, archive.getDescription());
        assertEquals(pattern, archive.getEventPattern());
        assertEquals(0, archive.getRetentionDays());
    }

    private static String patternOfLength(int length) {
        String pattern = "{\"source\":[\"" + "a".repeat(length - 15) + "\"]}";
        assertEquals(length, pattern.length());
        return pattern;
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
    void deleteRuleIsIdempotent() {
        service.putRule("my-rule", null, null, "rate(1 minute)", RuleState.ENABLED,
                null, null, null, REGION);
        service.deleteRule("my-rule", null, REGION);
        assertDoesNotThrow(() -> service.deleteRule("my-rule", null, REGION));

        assertTrue(service.listRules(null, null, REGION).isEmpty());
    }

    @Test
    void deleteRuleForMissingCustomBusThrowsResourceNotFound() {
        AwsException error = assertThrows(AwsException.class, () ->
                service.deleteRule("missing-rule", "missing-bus", REGION));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
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
        verify(dispatcherMock).dropPendingRetries(
                service.describeRule("my-rule", null, REGION).getArn(), List.of("t1"));
    }

    @Test
    void putTargetsStoresRetryPolicyAndDeadLetterConfig() {
        service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, REGION);
        Target target = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        target.setRoleArn("arn:aws:iam::000000000000:role/eventbridge-target");
        target.setRetryPolicy(new Target.RetryPolicy(4, 120));
        target.setDeadLetterConfig(new Target.DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"));

        service.putTargets("my-rule", null, List.of(target), REGION);

        Target stored = service.listTargetsByRule("my-rule", null, REGION).getFirst();
        assertEquals(target.getRoleArn(), stored.getRoleArn());
        assertEquals(new Target.RetryPolicy(4, 120), stored.getRetryPolicy());
        assertEquals(new Target.DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"),
                stored.getDeadLetterConfig());
    }

    @Test
    void crossAccountBusUpdateReplacesStoredTargetWithoutArn() {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, REGION);
        service.putTargets("role-rule", null, List.of(new Target("bus", null, null, null)), REGION);
        String arn = "arn:aws:events:us-east-1:111111111111:event-bus/destination";

        service.putTargets("role-rule", null, List.of(new Target("bus", arn, null, null)), REGION);

        List<Target> stored = service.listTargetsByRule("role-rule", null, REGION);
        assertEquals(1, stored.size());
        assertEquals(arn, stored.getFirst().getArn());
        assertNull(stored.getFirst().getRoleArn());
    }

    @ParameterizedTest
    @CsvSource({
            "aws, us-east-1",
            "aws-us-gov, us-gov-west-1",
            "aws-cn, cn-north-1"
    })
    void omittedRoleOnCrossAccountBusUpdateRetainsTheStoredRole(String partition, String region) {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, region);
        String arn = "arn:" + partition + ":events:" + region + ":111111111111:event-bus/destination";
        Target original = new Target("bus", arn, null, null);
        original.setRoleArn("arn:" + partition + ":iam::000000000000:role/eventbridge-target");
        service.putTargets("role-rule", null, List.of(original), region);

        Target updated = new Target("bus", arn, "{\"updated\":true}", null);
        service.putTargets("role-rule", null, List.of(updated), region);

        Target stored = service.listTargetsByRule("role-rule", null, region).getFirst();
        assertEquals(original.getRoleArn(), stored.getRoleArn());
        assertEquals(updated.getInput(), stored.getInput());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:events:us-east-1:000000000000:event-bus/local",
            "arn:aws:states:us-east-1:111111111111:stateMachine:workflow",
            "arn:aws:events:us-east-1:111111111111:rule/other"
    })
    void omittedRoleDoesNotRetainRolesForOtherTargetTypes(String arn) {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, REGION);
        Target original = new Target("target", arn, null, null);
        original.setRoleArn("arn:aws:iam::000000000000:role/original");
        service.putTargets("role-rule", null, List.of(original), REGION);

        service.putTargets("role-rule", null, List.of(new Target("target", arn, null, null)), REGION);

        assertNull(service.listTargetsByRule("role-rule", null, REGION).getFirst().getRoleArn());
    }

    @Test
    void replacingCrossAccountBusArnOrIdDoesNotCopyTheOldRole() {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, REGION);
        String arn = "arn:aws:events:us-east-1:111111111111:event-bus/original";
        Target original = new Target("bus", arn, null, null);
        original.setRoleArn("arn:aws:iam::000000000000:role/original");
        service.putTargets("role-rule", null, List.of(original), REGION);

        service.putTargets("role-rule", null, List.of(new Target("other-id", arn, null, null)), REGION);
        service.putTargets("role-rule", null, List.of(new Target("bus", arn + "-changed", null, null)), REGION);

        assertTrue(service.listTargetsByRule("role-rule", null, REGION).stream()
                .allMatch(target -> target.getRoleArn() == null));
    }

    @Test
    void explicitRoleUpdateReplacesCrossAccountBusRole() {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, REGION);
        String arn = "arn:aws:events:us-east-1:111111111111:event-bus/destination";
        Target original = new Target("bus", arn, null, null);
        original.setRoleArn("arn:aws:iam::000000000000:role/original");
        service.putTargets("role-rule", null, List.of(original), REGION);
        Target updated = new Target("bus", arn, null, null);
        updated.setRoleArn("arn:aws:iam::000000000000:role/replacement");

        service.putTargets("role-rule", null, List.of(updated), REGION);

        List<Target> stored = service.listTargetsByRule("role-rule", null, REGION);
        assertEquals(1, stored.size());
        assertEquals(updated.getRoleArn(), stored.getFirst().getRoleArn());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1601})
    void putTargetsRejectsRoleArnOutsideModelLengthBounds(int length) {
        Target valid = new Target("valid", "arn:aws:states:us-east-1:000000000000:stateMachine:valid", null, null);
        Target invalid = new Target("invalid", valid.getArn(), null, null);
        String role = "r".repeat(length);
        invalid.setRoleArn(role);
        String constraint = length == 0 ? "greater than or equal to 1" : "less than or equal to 1600";

        assertPutTargetsValidation("1 validation error detected: Value '" + role + "' at "
                + "'targets.2.member.roleArn' failed to satisfy constraint: Member must have length " + constraint,
                valid, invalid);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1600})
    void putTargetsAcceptsRoleArnAtModelLengthBounds(int length) {
        service.putRule("role-rule", null, "{}", null, RuleState.ENABLED, null, null, null, REGION);
        Target target = new Target("workflow", "arn:aws:states:us-east-1:000000000000:stateMachine:example", null, null);
        target.setRoleArn("r".repeat(length));

        service.putTargets("role-rule", null, List.of(target), REGION);

        assertEquals(target.getRoleArn(), service.listTargetsByRule("role-rule", null, REGION).getFirst().getRoleArn());
    }

    @Test
    void putTargetsRejectsRetryAttemptsAboveMaximumBeforeLookingUpTheRule() {
        Target target = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        target.setRetryPolicy(new Target.RetryPolicy(186, null));

        assertPutTargetsValidation("1 validation error detected: Value '186' at "
                + "'targets.1.member.retryPolicy.maximumRetryAttempts' failed to satisfy constraint: "
                + "Member must have value less than or equal to 185", target);
    }

    @Test
    void putTargetsRejectsEventAgeBelowMinimumOnTheSecondTarget() {
        Target valid = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        valid.setRetryPolicy(new Target.RetryPolicy(0, 60));
        Target invalid = new Target("t2", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        invalid.setRetryPolicy(new Target.RetryPolicy(null, 59));

        assertPutTargetsValidation("1 validation error detected: Value '59' at "
                + "'targets.2.member.retryPolicy.maximumEventAgeInSeconds' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 60", valid, invalid);
    }

    @Test
    void putTargetsRejectsEmptyDeadLetterArn() {
        Target target = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        target.setDeadLetterConfig(new Target.DeadLetterConfig(""));

        assertPutTargetsValidation("1 validation error detected: Value '' at "
                + "'targets.1.member.deadLetterConfig.arn' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", target);
    }

    @Test
    void putTargetsRejectsDeadLetterArnAboveMaximumLength() {
        String arn = "a".repeat(1601);
        Target target = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue", null, null);
        target.setDeadLetterConfig(new Target.DeadLetterConfig(arn));

        assertPutTargetsValidation("1 validation error detected: Value '" + arn + "' at "
                + "'targets.1.member.deadLetterConfig.arn' failed to satisfy constraint: "
                + "Member must have length less than or equal to 1600", target);
    }

    @Test
    @SuppressWarnings("unchecked")
    void putEventsHandsTheDispatcherTheRuleArnAndItsCurrentTargets() {
        Rule rule = service.putRule("my-rule", null, "{\"source\":[\"my.app\"]}", null, RuleState.ENABLED,
                null, null, null, REGION);
        Target first = new Target("t1", "arn:aws:sqs:us-east-1:000000000000:queue-1", null, null);
        service.putTargets("my-rule", null, List.of(first), REGION);

        service.putEvents(List.of(Map.of("Source", "my.app", "DetailType", "Test", "Detail", "{}")), REGION);

        ArgumentCaptor<Supplier<List<Target>>> currentTargets = ArgumentCaptor.forClass(Supplier.class);
        verify(dispatcherMock).dispatch(eq(rule.getArn()), eq(first), anyString(), eq(REGION),
                currentTargets.capture());
        Target second = new Target("t2", "arn:aws:sqs:us-east-1:000000000000:queue-2", null, null);
        service.putTargets("my-rule", null, List.of(second), REGION);
        assertEquals(List.of("t1", "t2"),
                currentTargets.getValue().get().stream().map(Target::getId).toList());
    }

    private void assertPutTargetsValidation(String expectedMessage, Target... targets) {
        AwsException error = assertThrows(AwsException.class, () ->
                service.putTargets("missing-rule", null, List.of(targets), REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(expectedMessage, error.getMessage());
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
        verify(dispatcherMock).dispatch(anyString(), eq(target), any(String.class), eq(REGION), any());
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
        verify(dispatcherMock).dispatch(anyString(), eq(target), any(String.class), eq(REGION), any());
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
        verify(dispatcherMock).dispatch(anyString(), eq(target), any(String.class), eq(REGION), any());
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

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(dispatcherMock).dispatch(anyString(), eq(target), json.capture(), eq("eu-west-1"), any());
        JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
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

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(dispatcherMock).dispatch(anyString(), eq(target), json.capture(), eq("us-west-2"), any());
        JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
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

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(dispatcherMock).dispatch(anyString(), eq(target), json.capture(), eq("eu-west-1"), any());
        JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
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

        verify(dispatcherMock, Mockito.never()).dispatch(any(), eq(target), any(), any(), any());
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

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(dispatcherMock).dispatch(anyString(), eq(target), json.capture(), eq(REGION), any());
        JsonNode envelope = OBJECT_MAPPER.readTree(json.getValue());
        assertEquals(REGION, envelope.path("region").asText());
        assertEquals("000000000000", envelope.path("account").asText());
    }

    @Test
    void createAndDescribeApiDestination() {
        Connection conn = service.createConnection("my-conn", "test conn", "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-api-key\",\"ApiKeyValue\":\"secret\"}}",
                null, null, REGION);

        ApiDestination dest = service.createApiDestination(
                "my-dest", "description", conn.getConnectionArn(),
                "https://api.example.com/events", "POST", 10, REGION);

        assertNotNull(dest.getArn());
        assertEquals("my-dest", dest.getName());
        assertEquals("description", dest.getDescription());
        assertEquals(conn.getConnectionArn(), dest.getConnectionArn());
        assertEquals("https://api.example.com/events", dest.getInvocationEndpoint());
        assertEquals("POST", dest.getHttpMethod());
        assertEquals(10, dest.getInvocationRateLimitPerSecond());
        assertEquals(ApiDestinationState.ACTIVE, dest.getApiDestinationState());

        ApiDestination described = service.describeApiDestination("my-dest", REGION);
        assertEquals(dest.getArn(), described.getArn());
        assertEquals("my-dest", described.getName());
    }

    @Test
    void createApiDestination_validations() {
        Connection conn = service.createConnection("conn-val", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);

        // Name missing
        assertThrows(AwsException.class, () -> service.createApiDestination(
                null, "desc", conn.getConnectionArn(), "https://api.com", "POST", 5, REGION));

        // Invalid method
        assertThrows(AwsException.class, () -> service.createApiDestination(
                "dest-1", "desc", conn.getConnectionArn(), "https://api.com", "INVALID_METHOD", 5, REGION));

        // Non-existent connection
        assertThrows(AwsException.class, () -> service.createApiDestination(
                "dest-2", "desc", "arn:aws:events:us-east-1:000000000000:connection/unknown/123",
                "https://api.com", "POST", 5, REGION));

        // Duplicate name
        service.createApiDestination("dup-dest", "desc", conn.getConnectionArn(), "https://api.com", "POST", 5, REGION);
        assertThrows(AwsException.class, () -> service.createApiDestination(
                "dup-dest", "desc2", conn.getConnectionArn(), "https://api.com", "GET", 5, REGION));
    }

    @Test
    void createApiDestination_rejectsNonHttpEndpoint() {
        Connection conn = service.createConnection("conn-endpoint", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);

        for (String endpoint : List.of("ftp://example.com/x", "file:///etc/passwd", "https:///no-host",
                "https://:8080/path", "https://*.example.com/hook", "not a url")) {
            AwsException ex = assertThrows(AwsException.class, () -> service.createApiDestination(
                    "bad-endpoint", null, conn.getConnectionArn(), endpoint, "POST", null, REGION));
            assertEquals("ValidationException", ex.getErrorCode());
        }
        assertThrows(AwsException.class, () -> service.describeApiDestination("bad-endpoint", REGION));

        ApiDestination wildcardPath = service.createApiDestination("wildcard-path", null, conn.getConnectionArn(),
                "https://api.example.com/hook/*", "POST", null, REGION);
        assertEquals("https://api.example.com/hook/*", wildcardPath.getInvocationEndpoint());
    }

    @Test
    void updateApiDestination_leavesDestinationUntouchedWhenAnyFieldIsInvalid() {
        Connection conn = service.createConnection("conn-atomic", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);
        service.createApiDestination("atomic-upd", "original", conn.getConnectionArn(),
                "https://api.com/v1", "POST", null, REGION);

        assertThrows(AwsException.class, () -> service.updateApiDestination(
                "atomic-upd", "changed", null, null, "INVALID_METHOD", null, REGION));

        ApiDestination described = service.describeApiDestination("atomic-upd", REGION);
        assertEquals("original", described.getDescription());
        assertEquals("POST", described.getHttpMethod());
    }

    @Test
    void updateApiDestination_rejectsNonHttpEndpoint() {
        Connection conn = service.createConnection("conn-endpoint-upd", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);
        service.createApiDestination("endpoint-upd", null, conn.getConnectionArn(),
                "https://api.com/v1", "POST", null, REGION);

        AwsException ex = assertThrows(AwsException.class, () -> service.updateApiDestination(
                "endpoint-upd", null, null, "file:///etc/passwd", null, null, REGION));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals("https://api.com/v1",
                service.describeApiDestination("endpoint-upd", REGION).getInvocationEndpoint());
    }

    @Test
    void findApiDestinationByArn_ignoresAnArnFromBeforeTheDestinationWasRecreated() {
        Connection conn = service.createConnection("conn-recreate", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);
        ApiDestination first = service.createApiDestination("recreated", null, conn.getConnectionArn(),
                "https://api.com/v1", "POST", null, REGION);
        service.deleteApiDestination("recreated", REGION);
        ApiDestination second = service.createApiDestination("recreated", null, conn.getConnectionArn(),
                "https://api.com/v2", "POST", null, REGION);

        assertNull(service.findApiDestinationByArn(first.getArn(), REGION));
        assertEquals("https://api.com/v2",
                service.findApiDestinationByArn(second.getArn(), REGION).getInvocationEndpoint());
    }

    @Test
    void restoreApiDestination_putsBackUnsetFieldsToo() {
        Connection conn = service.createConnection("conn-restore", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);
        service.createApiDestination("restored", "changed", conn.getConnectionArn(),
                "https://api.com/v2", "PUT", 5, REGION);

        service.restoreApiDestination("restored", null, conn.getConnectionArn(),
                "https://api.com/v1", "POST", null, REGION);

        ApiDestination restored = service.describeApiDestination("restored", REGION);
        assertNull(restored.getDescription());
        assertNull(restored.getInvocationRateLimitPerSecond());
        assertEquals("https://api.com/v1", restored.getInvocationEndpoint());
        assertEquals("POST", restored.getHttpMethod());
    }

    @Test
    void listApiDestinations_isSortedByName() {
        Connection conn = service.createConnection("conn-sorted", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"x-key\",\"ApiKeyValue\":\"val\"}}",
                null, null, REGION);
        for (String name : List.of("sorted-c", "sorted-a", "sorted-b")) {
            service.createApiDestination(name, null, conn.getConnectionArn(), "https://api.com", "GET", null, REGION);
        }

        List<String> names = service.listApiDestinations("sorted-", null, REGION).stream()
                .map(ApiDestination::getName).toList();
        assertEquals(List.of("sorted-a", "sorted-b", "sorted-c"), names);
    }

    @Test
    void updateAndDeleteApiDestination() {
        Connection conn1 = service.createConnection("conn-upd1", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"k\",\"ApiKeyValue\":\"v\"}}", null, null, REGION);
        Connection conn2 = service.createConnection("conn-upd2", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"k\",\"ApiKeyValue\":\"v\"}}", null, null, REGION);

        service.createApiDestination("upd-dest", "initial", conn1.getConnectionArn(), "https://api.com/v1", "POST", 5, REGION);

        ApiDestination updated = service.updateApiDestination("upd-dest", "updated desc", conn2.getConnectionArn(),
                "https://api.com/v2", "PUT", 20, REGION);

        assertEquals("updated desc", updated.getDescription());
        assertEquals(conn2.getConnectionArn(), updated.getConnectionArn());
        assertEquals("https://api.com/v2", updated.getInvocationEndpoint());
        assertEquals("PUT", updated.getHttpMethod());
        assertEquals(20, updated.getInvocationRateLimitPerSecond());

        service.deleteApiDestination("upd-dest", REGION);
        assertThrows(AwsException.class, () -> service.describeApiDestination("upd-dest", REGION));
    }

    @Test
    void listApiDestinations() {
        Connection conn = service.createConnection("conn-list", null, "API_KEY",
                "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"k\",\"ApiKeyValue\":\"v\"}}", null, null, REGION);

        service.createApiDestination("prefix-dest-1", null, conn.getConnectionArn(), "https://api.com", "GET", null, REGION);
        service.createApiDestination("prefix-dest-2", null, conn.getConnectionArn(), "https://api.com", "GET", null, REGION);
        service.createApiDestination("other-dest", null, conn.getConnectionArn(), "https://api.com", "GET", null, REGION);

        List<ApiDestination> all = service.listApiDestinations(null, null, REGION);
        assertTrue(all.size() >= 3);

        List<ApiDestination> prefixed = service.listApiDestinations("prefix-", null, REGION);
        assertEquals(2, prefixed.size());
    }
}
