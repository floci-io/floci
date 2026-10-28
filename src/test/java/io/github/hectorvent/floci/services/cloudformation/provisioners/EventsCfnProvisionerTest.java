package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.ApiDestination;
import io.github.hectorvent.floci.services.eventbridge.model.EcsParameters;
import io.github.hectorvent.floci.services.eventbridge.model.HttpParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Events CFN provisioner in isolation, with only {@link EventBridgeService} mocked. */
class EventsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/my-stack-Rule-ab12cd";

    private final EventBridgeService events = mock(EventBridgeService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventsCfnProvisioner provisioner = new EventsCfnProvisioner(events);

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.omitNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(value -> values.add(value.asText()));
            return values;
        });
        when(engine.resolveLiteralList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(value -> values.add(value.asText()));
            return values;
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setResourceType(type);
        r.setLogicalId(logicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private void stubPutRule() {
        Rule rule = new Rule();
        rule.setArn(RULE_ARN);
        when(events.putRule(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(rule);
    }

    private ObjectNode target(String id, String arn) {
        return mapper.createObjectNode().put("Id", id).put("Arn", arn);
    }

    private static Target storedTarget(String id) {
        return new Target(id, "arn:aws:sqs:us-east-1:000000000000:q-" + id, null, null);
    }

    // ── the rule keeps its identity across an update ──────────────────────────

    @Test
    void anUnnamedRuleKeepsItsGeneratedNameAcrossUpdates() {
        // The defect: generating unconditionally gave an unnamed rule a fresh random name on every
        // UpdateStack, creating a second rule and leaving the first behind still matching events.
        stubPutRule();
        StackResource r = resource("AWS::Events::Rule", "Rule");

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-Rule-ab12cd"));

        assertEquals("my-stack-Rule-ab12cd", r.getPhysicalId());
        assertEquals("my-stack-Rule-ab12cd", r.getAttributes().get("RuleName"));
        verify(events).putRule(eq("my-stack-Rule-ab12cd"), any(), any(), any(), any(), any(), any(),
                any(), anyString());
    }

    @Test
    void anExplicitlyNamedRuleStillUsesTheTemplateName() {
        stubPutRule();
        StackResource r = resource("AWS::Events::Rule", "Rule");

        provisioner.provision(r, mapper.createObjectNode().put("Name", "orders"), ctx(null));

        assertEquals("orders", r.getPhysicalId());
        assertEquals(RULE_ARN, r.getAttributes().get("Arn"));
        assertEquals("orders", r.getAttributes().get("RuleName"));
    }

    // ── targets are driven to the template's desired state ───────────────────

    @Test
    void aTargetDroppedFromTheTemplateIsRemovedFromTheRule() {
        // PutTargets only upserts by id, so without the sweep a dropped target stayed on the rule
        // and kept receiving events after the template stopped declaring it.
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("keep"), storedTarget("gone")));
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(target("keep", "arn:aws:sqs:us-east-1:000000000000:q-keep"));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx("orders"));

        ArgumentCaptor<List<String>> removed = ArgumentCaptor.forClass(List.class);
        verify(events).removeTargets(eq("orders"), any(), removed.capture(), anyString());
        assertEquals(List.of("gone"), removed.getValue());
    }

    @Test
    void aTemplateThatDeclaresNoTargetsClearsThem() {
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("stale")));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"),
                mapper.createObjectNode().put("Name", "orders"), ctx("orders"));

        ArgumentCaptor<List<String>> removed = ArgumentCaptor.forClass(List.class);
        verify(events).removeTargets(eq("orders"), any(), removed.capture(), anyString());
        assertEquals(List.of("stale"), removed.getValue());
        verify(events, never()).putTargets(anyString(), any(), any(), anyString());
    }

    @Test
    void anUnchangedTargetSetRemovesNothing() {
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("keep")));
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(target("keep", "arn:aws:sqs:us-east-1:000000000000:q-keep"));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx("orders"));

        verify(events).putTargets(eq("orders"), any(), any(), anyString());
        verify(events, never()).removeTargets(anyString(), any(), any(), anyString());
    }

    // ── each target carries what PutTargets would set ────────────────────────

    @Test
    void aTargetKeepsItsDeadLetterConfigRetryPolicyRoleAndEcsParameters() throws Exception {
        // The provisioner copied Id, Arn, the inputs, SqsParameters and BatchParameters only, so a
        // stack's dead-letter queue and retry policy never reached delivery and an undeliverable
        // event was logged and lost.
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(mapper.readTree("""
                {"Id": "Run", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                 "RoleArn": "arn:aws:iam::000000000000:role/events-run-task",
                 "DeadLetterConfig": {"Arn": "arn:aws:sqs:us-east-1:000000000000:orders-dlq"},
                 "RetryPolicy": {"MaximumRetryAttempts": 3, "MaximumEventAgeInSeconds": 120},
                 "EcsParameters": {
                   "TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                   "TaskCount": 2, "LaunchType": "FARGATE",
                   "NetworkConfiguration": {"AwsVpcConfiguration": {
                     "Subnets": ["subnet-1"], "SecurityGroups": ["sg-1"], "AssignPublicIp": "DISABLED"}},
                   "PlacementStrategies": [{"Type": "spread", "Field": "attribute:ecs.availability-zone"}],
                   "PlacementConstraints": [{"Type": "memberOf", "Expression": "attribute:ecs.os-type == linux"}],
                   "CapacityProviderStrategy": [{"CapacityProvider": "FARGATE_SPOT", "Weight": 1, "Base": 0}],
                   "TagList": [{"Key": "team", "Value": "jobs"}]}}
                """));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx(null));

        Target put = onlyPutTarget();
        assertEquals("arn:aws:iam::000000000000:role/events-run-task", put.getRoleArn());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:orders-dlq", put.getDeadLetterConfig().arn());
        assertEquals(new Target.RetryPolicy(3, 120), put.getRetryPolicy());
        EcsParameters ecs = put.getEcsParameters();
        assertEquals("arn:aws:ecs:us-east-1:000000000000:task-definition/job:1", ecs.getTaskDefinitionArn());
        assertEquals(2, ecs.getTaskCount());
        assertEquals("FARGATE", ecs.getLaunchType());
        assertEquals(List.of("subnet-1"), ecs.getNetworkConfiguration().getAwsvpcConfiguration().getSubnets());
        assertEquals("DISABLED", ecs.getNetworkConfiguration().getAwsvpcConfiguration().getAssignPublicIp());
        assertEquals(mapper.readTree("[{\"type\": \"spread\", \"field\": \"attribute:ecs.availability-zone\"}]"),
                ecs.getPlacementStrategy());
        assertEquals(mapper.readTree("[{\"type\": \"memberOf\", \"expression\": \"attribute:ecs.os-type == linux\"}]"),
                ecs.getPlacementConstraints());
        assertEquals(mapper.readTree("[{\"capacityProvider\": \"FARGATE_SPOT\", \"weight\": 1, \"base\": 0}]"),
                ecs.getCapacityProviderStrategy());
        assertEquals(mapper.readTree("[{\"Key\": \"team\", \"Value\": \"jobs\"}]"), ecs.getTags());
    }

    @Test
    void aRetryPolicyResolvedToTextIsReadAsNumbers() throws Exception {
        // A Ref or Fn::Sub resolves to text, which the template still means as an integer.
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(mapper.readTree("""
                {"Id": "Q", "Arn": "arn:aws:sqs:us-east-1:000000000000:q",
                 "RetryPolicy": {"MaximumRetryAttempts": "0", "MaximumEventAgeInSeconds": "60"}}
                """));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx(null));

        assertEquals(new Target.RetryPolicy(0, 60), onlyPutTarget().getRetryPolicy());
    }

    /**
     * With a real engine: a member or list item that is AWS::NoValue, directly or as the Fn::If
     * branch taken, is left out, as CloudFormation leaves it out of PutTargets, at any depth. A
     * literal empty string is a value and stays, such as a tag's empty Value.
     */
    @Test
    void membersThatAreNoValueAreLeftOutAndEmptyStringsKept() throws Exception {
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(mapper.readTree("""
                {"Id": "Q", "Arn": "arn:aws:sqs:us-east-1:000000000000:q", "RoleArn": {"Ref": "AWS::NoValue"},
                 "RetryPolicy": {"MaximumRetryAttempts": {"Ref": "AWS::NoValue"}, "MaximumEventAgeInSeconds": "60"},
                 "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/jobs:1",
                                   "TaskCount": {"Ref": "AWS::NoValue"},
                                   "TagList": [{"Ref": "AWS::NoValue"}, {"Key": "team", "Value": ""}]},
                 "DeadLetterConfig": {"Ref": "AWS::NoValue"}}
                """));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, new ProvisionContext(
                CloudFormationTemplateEngine.standalone("000000000000", REGION, "my-stack", mapper, null),
                REGION, "000000000000", "my-stack", null));

        Target put = onlyPutTarget();
        assertNull(put.getRoleArn());
        assertNull(put.getDeadLetterConfig());
        assertEquals(new Target.RetryPolicy(null, 60), put.getRetryPolicy());
        assertNull(put.getEcsParameters().getTaskCount());
        assertEquals(mapper.readTree("[{\"Key\": \"team\", \"Value\": \"\"}]"), put.getEcsParameters().getTags());
    }

    @Test
    void aTargetWithoutThemLeavesThemUnset() {
        // Unset means EventBridge's defaults (185 attempts, 24 hours, no dead-letter queue).
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(target("Q", "arn:aws:sqs:us-east-1:000000000000:q"));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx(null));

        Target put = onlyPutTarget();
        assertNull(put.getRetryPolicy());
        assertNull(put.getDeadLetterConfig());
        assertNull(put.getRoleArn());
        assertNull(put.getEcsParameters());
    }

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-18 and 2026-10-19): a member the resource schema
     * types fails template validation, naming the member, the list index or the map key, or the
     * handler's model validation, naming the whole path, before the rule exists.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("mistypedMembers")
    void aMemberOfTheWrongTypeFailsBeforeTheRuleExists(String members, String code, String message) throws Exception {
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(((ObjectNode) mapper.readTree(members))
                .put("Id", "T").put("Arn", "arn:aws:ecs:us-east-1:000000000000:cluster/jobs"));

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx(null)));

        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
        verify(events, never()).putRule(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(events, never()).putTargets(anyString(), any(), any(), anyString());
    }

    static Stream<Arguments> mistypedMembers() {
        String template = "ValidationError";
        String model = "InvalidRequest";
        String range = ". This may occur when a numeric value exceeds 32-bit integer max (2147483647)."
                + " Consider using 'number' type or ensure value fits in integer range.";
        return Stream.of(
                Arguments.of("{\"RetryPolicy\": \"x\"}", template,
                        "Property [RetryPolicy] expected type: JSONObject, found: String"),
                Arguments.of("{\"RetryPolicy\": 5}", template,
                        "Property [RetryPolicy] expected type: JSONObject, found: String"),
                Arguments.of("{\"RetryPolicy\": []}", template,
                        "Property [RetryPolicy] expected type: JSONObject, found: JSONArray"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": \"abc\"}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: String"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": 4.5}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: Float"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": \"4.5\"}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: Float"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": true}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: Boolean"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": {\"a\": 1}}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: JSONObject"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": [1]}}", template,
                        "Property [MaximumRetryAttempts] expected type: Integer, found: JSONArray"),
                Arguments.of("{\"DeadLetterConfig\": \"arn:aws:sqs:us-east-1:000000000000:dlq\"}", template,
                        "Property [DeadLetterConfig] expected type: JSONObject, found: String"),
                Arguments.of("{\"DeadLetterConfig\": {\"Arn\": {\"a\": 1}}}", template,
                        "Property [Arn] expected type: String, found: JSONObject"),
                Arguments.of("{\"EcsParameters\": \"x\"}", template,
                        "Property [EcsParameters] expected type: JSONObject, found: String"),
                Arguments.of("{\"EcsParameters\": {\"TaskCount\": \"abc\"}}", template,
                        "Property [TaskCount] expected type: Integer, found: String"),
                Arguments.of("{\"EcsParameters\": {\"NetworkConfiguration\": \"x\"}}", template,
                        "Property [NetworkConfiguration] expected type: JSONObject, found: String"),
                Arguments.of("{\"EcsParameters\": {\"NetworkConfiguration\": {\"AwsVpcConfiguration\": "
                        + "{\"Subnets\": \"subnet-1\"}}}}", template,
                        "Property [Subnets] expected type: JSONArray, found: String"),
                Arguments.of("{\"EcsParameters\": {\"NetworkConfiguration\": {\"AwsVpcConfiguration\": "
                        + "{\"Subnets\": [{\"a\": 1}]}}}}", template,
                        "Property [0] expected type: String, found: JSONObject"),
                Arguments.of("{\"EcsParameters\": {\"TagList\": {\"Key\": \"a\", \"Value\": \"b\"}}}", template,
                        "Property [TagList] expected type: JSONArray, found: JSONObject"),
                Arguments.of("{\"EcsParameters\": {\"TagList\": [\"a\"]}}", template,
                        "Property [0] expected type: JSONObject, found: String"),
                Arguments.of("{\"EcsParameters\": {\"EnableECSManagedTags\": {\"a\": 1}}}", template,
                        "Property [EnableECSManagedTags] expected type: Boolean, found: JSONObject"),
                Arguments.of("{\"EcsParameters\": {\"EnableECSManagedTags\": [true]}}", template,
                        "Property [EnableECSManagedTags] expected type: Boolean, found: JSONArray"),
                Arguments.of("{\"EcsParameters\": {\"Group\": {\"a\": 1}}}", template,
                        "Property [Group] expected type: String, found: JSONObject"),
                Arguments.of("{\"EcsParameters\": {\"Group\": [\"a\"]}}", template,
                        "Property [Group] expected type: String, found: JSONArray"),
                Arguments.of("{\"Input\": {\"a\": 1}}", template,
                        "Property [Input] expected type: String, found: JSONObject"),
                Arguments.of("{\"InputPath\": [\"$.a\"]}", template,
                        "Property [InputPath] expected type: String, found: JSONArray"),
                Arguments.of("{\"InputTransformer\": {\"InputTemplate\": \"x\", \"InputPathsMap\": \"x\"}}", template,
                        "Property [InputPathsMap] expected type: JSONObject, found: String"),
                Arguments.of("{\"InputTransformer\": {\"InputTemplate\": \"x\", \"InputPathsMap\": {\"a\": {\"b\": 1}}}}",
                        template, "Property [a] expected type: String, found: JSONObject"),
                Arguments.of("{\"BatchParameters\": {\"JobDefinition\": \"d\", \"JobName\": \"n\","
                        + " \"ArrayProperties\": {\"Size\": \"abc\"}}}", template,
                        "Property [Size] expected type: Integer, found: String"),
                Arguments.of("{\"HttpParameters\": {\"HeaderParameters\": {\"h\": {\"x\": 1}}}}", template,
                        "Property [h] expected type: String, found: JSONObject"),
                Arguments.of("{\"SqsParameters\": \"x\"}", template,
                        "Property [SqsParameters] expected type: JSONObject, found: String"),
                Arguments.of("{\"RetryPolicy\": {\"MaximumRetryAttempts\": 3000000000}}", model,
                        "Model validation failed (#/Targets/0/RetryPolicy/MaximumRetryAttempts:"
                                + " expected type: Integer, found: String" + range + ")"),
                Arguments.of("{\"EcsParameters\": {\"TaskCount\": \"3000000000\"}}", model,
                        "Model validation failed (#/Targets/0/EcsParameters/TaskCount:"
                                + " expected type: Integer, found: String" + range + ")"),
                Arguments.of("{\"EcsParameters\": {\"EnableECSManagedTags\": 1}}", model,
                        "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:"
                                + " expected type: Boolean, found: String)"),
                Arguments.of("{\"EcsParameters\": {\"EnableECSManagedTags\": \"yes\"}}", model,
                        "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:"
                                + " expected type: Boolean, found: String)"));
    }

    @Test
    void templateValidationFailsBeforeAnEarlierTargetFailsModelValidation() throws Exception {
        // CloudFormation validates the whole template before the handler validates its model.
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets")
                .add(mapper.readTree("""
                        {"Id": "A", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                         "EcsParameters": {"EnableECSManagedTags": "yes"}}
                        """))
                .add(mapper.readTree("""
                        {"Id": "B", "Arn": "arn:aws:sqs:us-east-1:000000000000:q", "RetryPolicy": "x"}
                        """));

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx(null)));

        assertEquals("Property [RetryPolicy] expected type: JSONObject, found: String", e.getMessage());
    }

    @Test
    void valuesAreConvertedAsCloudFormationConvertsThem() throws Exception {
        // Measured: "TRUE" is true, a number in a list of strings is its text, and an integer
        // given as text is the integer.
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(mapper.readTree("""
                {"Id": "Run", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                 "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                   "TaskCount": "3", "EnableECSManagedTags": "TRUE",
                   "NetworkConfiguration": {"AwsVpcConfiguration": {"Subnets": [5]}}}}
                """));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, new ProvisionContext(
                CloudFormationTemplateEngine.standalone("000000000000", REGION, "my-stack", mapper, null),
                REGION, "000000000000", "my-stack", null));

        EcsParameters ecs = onlyPutTarget().getEcsParameters();
        assertEquals(3, ecs.getTaskCount());
        assertEquals(Boolean.TRUE, ecs.getEnableECSManagedTags());
        assertEquals(List.of("5"), ecs.getNetworkConfiguration().getAwsvpcConfiguration().getSubnets());
    }

    @Test
    void aLiteralListKeepsItsBlankEntriesWhereTheTemplatePutThem() throws Exception {
        // Measured: SecurityGroups ["", "sg-..."] reaches the target as both. Path parameter values
        // are positional, so a blank one dropped would move the next into its wildcard.
        stubPutRule();
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(mapper.readTree("""
                {"Id": "Run", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                 "HttpParameters": {"PathParameterValues": ["", "b"]},
                 "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                   "NetworkConfiguration": {"AwsVpcConfiguration": {"Subnets": ["subnet-1"], "SecurityGroups": ["", "sg-1"]}}}}
                """));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, new ProvisionContext(
                CloudFormationTemplateEngine.standalone("000000000000", REGION, "my-stack", mapper, null),
                REGION, "000000000000", "my-stack", null));

        Target put = onlyPutTarget();
        assertEquals(List.of("", "b"), put.getHttpParameters().getPathParameterValues());
        assertEquals(List.of("", "sg-1"),
                put.getEcsParameters().getNetworkConfiguration().getAwsvpcConfiguration().getSecurityGroups());
    }

    private Target onlyPutTarget() {
        ArgumentCaptor<List<Target>> put = ArgumentCaptor.forClass(List.class);
        verify(events).putTargets(eq("orders"), any(), put.capture(), anyString());
        assertEquals(1, put.getValue().size());
        return put.getValue().getFirst();
    }

    // ── a failed policy removal is not swallowed ─────────────────────────────

    @Test
    void anAlreadyGonePolicyStatementCountsAsDeleted() {
        doThrowOnRemovePermission(new AwsException("ResourceNotFoundException",
                "Statement not found: sid-1", 400));

        provisioner.delete("AWS::Events::EventBusPolicy", "orders-bus|sid-1", REGION);

        verify(events).removePermission("orders-bus", "sid-1", false, REGION);
    }

    @Test
    void aFailedPolicyRemovalReachesTheStack() {
        // The previous catch-all logged every failure at debug and returned, so the statement
        // stayed on the bus while the stack still reported DELETE_COMPLETE.
        doThrowOnRemovePermission(new AwsException("ValidationException", "StatementId is required.", 400));

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Events::EventBusPolicy", "orders-bus|sid-1", REGION));

        assertEquals("ValidationException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("StatementId"));
    }

    // ── the event bus reads its prior identity from the provision context ─────

    @Test
    void anEventBusUpdateComparesAgainstThePriorIdFromTheContext() {
        // provision assigns the new id as it runs, so the prior name has to come from the context.
        // Reading it off the resource made a rename look like a create and silently switched buses.
        StackResource r = resource("AWS::Events::EventBus", "Bus");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                r, mapper.createObjectNode().put("Name", "renamed"), ctx("orders-bus")));

        assertTrue(thrown.getMessage().contains("requires resource replacement"));
    }

    // ── the policy statement id is held to the modeled pattern ────────────────

    @Test
    void aStatementIdCarryingTheIdSeparatorIsRejected() {
        // AWS models StatementId as [a-zA-Z0-9-_]+, so a pipe cannot reach the service. Refusing it
        // is parity first, and it also keeps "<bus>|<sid>" splittable back into the pair.
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Events::EventBusPolicy", "Policy"),
                policyProps("orders-bus|sid"), ctx(null)));

        assertEquals("ValidationException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("StatementId"));
    }

    @Test
    void aStatementIdBeyondTheModeledLengthIsRejected() {
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Events::EventBusPolicy", "Policy"),
                policyProps("s".repeat(65)), ctx(null)));

        assertEquals("ValidationException", thrown.getErrorCode());
        verify(events, never()).putPermission(anyString(), any(), any(), anyString(), any(), any(), anyString());
    }

    private ObjectNode policyProps(String statementId) {
        return mapper.createObjectNode()
                .put("EventBusName", "orders-bus")
                .put("StatementId", statementId)
                .put("Action", "events:PutEvents")
                .put("Principal", "111122223333");
    }

    private void doThrowOnRemovePermission(AwsException e) {
        Mockito.doThrow(e).when(events)
                .removePermission(anyString(), anyString(), ArgumentMatchers.anyBoolean(), anyString());
    }

    // ── a failed stack update puts the rule's targets back ────────────────────

    private static final String RULE_TARGETS_SNAPSHOT_ATTR = CfnRollback.RULE_TARGETS_SNAPSHOT_ATTR;

    @Test
    void aRollbackPutsBackATargetTheUpdateDropped() {
        // Before the snapshot, the reconciliation sweep could delete a live target and a later
        // resource's failure left it deleted: the rule stopped delivering to it for good.
        stubPutRule();
        stubTargetSequence(List.of(storedTarget("keep"), storedTarget("gone")),
                List.of(storedTarget("keep"), storedTarget("gone")),
                List.of(storedTarget("keep")));
        StackResource r = updatedRuleWithTargets("keep");

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals(List.of("keep", "gone"), idsOfPutTargets(2).get(1));
        assertNull(r.getAttributes().get(RULE_TARGETS_SNAPSHOT_ATTR));
    }

    @Test
    void aRollbackRemovesATargetTheUpdateAdded() {
        // The restore is exact, not additive: what the failed update added does not survive it.
        stubPutRule();
        stubTargetSequence(List.of(storedTarget("keep")),
                List.of(storedTarget("keep"), storedTarget("added")),
                List.of(storedTarget("keep"), storedTarget("added")));
        StackResource r = updatedRuleWithTargets("keep", "added");

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<List<String>> removed = ArgumentCaptor.forClass(List.class);
        verify(events).removeTargets(eq("orders"), any(), removed.capture(), anyString());
        assertEquals(List.of("added"), removed.getValue());
        assertEquals(List.of("keep"), idsOfPutTargets(2).get(1));
    }

    @Test
    void aRuleThisRunNeverTouchedIsNotRolledBack() {
        // Answering true here would clear the targets of a rule the update never reconciled. False
        // is what makes the stack keep reporting that rollback is not implemented for it.
        StackResource r = resource("AWS::Events::Rule", "Rule");
        r.setPhysicalId("orders");

        assertFalse(provisioner.rollbackUpdate(r));

        verify(events, never()).putTargets(anyString(), any(), any(), anyString());
        verify(events, never()).removeTargets(anyString(), any(), any(), anyString());
    }

    @Test
    void aFailedReconciliationRestoresTheTargetsAndRethrowsTheFailure() {
        stubPutRule();
        stubTargetSequence(List.of(storedTarget("keep"), storedTarget("gone")),
                List.of(storedTarget("keep"), storedTarget("gone")));
        AwsException putFailure = new AwsException("ValidationException", "Too many targets", 400);
        when(events.putTargets(anyString(), any(), any(), anyString())).thenThrow(putFailure).thenReturn(1);
        StackResource r = resource("AWS::Events::Rule", "Rule");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, ruleProps("keep"), ctx("orders")));

        assertSame(putFailure, thrown);
        assertEquals(List.of("keep", "gone"), idsOfPutTargets(2).get(1));
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertNull(r.getAttributes().get(RULE_TARGETS_SNAPSHOT_ATTR));
    }

    @Test
    void aRestoreThatAlsoFailsIsRecordedOnTheResource() {
        // The stack has to report UPDATE_ROLLBACK_FAILED naming this rule rather than claiming the
        // prior targets are live, so the restore failure goes on the resource instead of being
        // thrown over the failure that is already on its way out.
        stubPutRule();
        stubTargetSequence(List.of(storedTarget("keep"), storedTarget("gone")),
                List.of(storedTarget("keep"), storedTarget("gone")));
        AwsException putFailure = new AwsException("ValidationException", "Too many targets", 400);
        doThrow(putFailure).when(events).putTargets(anyString(), any(), any(), anyString());
        StackResource r = resource("AWS::Events::Rule", "Rule");

        assertThrows(AwsException.class, () -> provisioner.provision(r, ruleProps("keep"), ctx("orders")));

        assertEquals("Too many targets", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aCreateTakesNoSnapshotToRollBack() {
        stubPutRule();

        StackResource r = resource("AWS::Events::Rule", "Rule");
        provisioner.provision(r, ruleProps("keep"), ctx(null));

        assertNull(r.getAttributes().get(RULE_TARGETS_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }

    /** Runs an update that reconciles the rule down to {@code desiredIds}, and returns the resource. */
    private StackResource updatedRuleWithTargets(String... desiredIds) {
        StackResource r = resource("AWS::Events::Rule", "Rule");
        provisioner.provision(r, ruleProps(desiredIds), ctx("orders"));
        return r;
    }

    private ObjectNode ruleProps(String... targetIds) {
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        ArrayNode declared = props.putArray("Targets");
        for (String id : targetIds) {
            declared.add(target(id, "arn:aws:sqs:us-east-1:000000000000:q-" + id));
        }
        return props;
    }

    /** Successive answers from ListTargetsByRule, in the order the run asks for them. */
    @SafeVarargs
    private void stubTargetSequence(List<Target> first, List<Target>... rest) {
        OngoingStubbing<List<Target>> stub = when(events.listTargetsByRule(anyString(), any(), anyString())).thenReturn(first);
        for (List<Target> answer : rest) {
            stub = stub.thenReturn(answer);
        }
    }

    /** The target ids of each PutTargets call, in order, asserting there were exactly {@code n}. */
    private List<List<String>> idsOfPutTargets(int n) {
        ArgumentCaptor<List<Target>> put = ArgumentCaptor.forClass(List.class);
        verify(events, times(n)).putTargets(eq("orders"), any(), put.capture(), anyString());
        return put.getAllValues().stream().map(call -> call.stream().map(Target::getId).toList()).toList();
    }

    // ── ApiDestination provisioning and deleting ──────────────────────────────

    @Test
    void provisionApiDestinationCreatesResource() {
        ApiDestination destination = new ApiDestination(
                "orders-dest",
                "arn:aws:events:us-east-1:000000000000:api-destination/orders-dest/12345678",
                "arn:aws:events:us-east-1:000000000000:connection/conn/111",
                "https://api.example.com/orders",
                "POST",
                10,
                "Orders destination");
        when(events.createApiDestination(eq("orders-dest"), eq("Orders destination"),
                eq("arn:aws:events:us-east-1:000000000000:connection/conn/111"),
                eq("https://api.example.com/orders"), eq("POST"), eq(10), eq(REGION)))
                .thenReturn(destination);

        StackResource r = resource("AWS::Events::ApiDestination", "MyDestination");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "orders-dest")
                .put("ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/conn/111")
                .put("InvocationEndpoint", "https://api.example.com/orders")
                .put("HttpMethod", "POST")
                .put("InvocationRateLimitPerSecond", 10)
                .put("Description", "Orders destination");

        provisioner.provision(r, props, ctx(null));

        assertEquals("orders-dest", r.getPhysicalId());
        assertEquals("arn:aws:events:us-east-1:000000000000:api-destination/orders-dest/12345678",
                r.getAttributes().get("Arn"));
        assertEquals("arn:aws:events:us-east-1:000000000000:api-destination/orders-dest",
                r.getAttributes().get("ArnForPolicy"));
        assertEquals("orders-dest", r.getAttributes().get("Name"));
    }

    @Test
    void provisionApiDestinationUpdatesResource() {
        ApiDestination destination = new ApiDestination(
                "orders-dest",
                "arn:aws:events:us-east-1:000000000000:api-destination/orders-dest/12345678",
                "arn:aws:events:us-east-1:000000000000:connection/conn/222",
                "https://api.example.com/v2/orders",
                "PUT",
                20,
                "Updated destination");
        when(events.updateApiDestination(eq("orders-dest"), eq("Updated destination"),
                eq("arn:aws:events:us-east-1:000000000000:connection/conn/222"),
                eq("https://api.example.com/v2/orders"), eq("PUT"), eq(20), eq(REGION)))
                .thenReturn(destination);
        when(events.describeApiDestination("orders-dest", REGION)).thenReturn(priorApiDestination());

        StackResource r = resource("AWS::Events::ApiDestination", "MyDestination");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "orders-dest")
                .put("ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/conn/222")
                .put("InvocationEndpoint", "https://api.example.com/v2/orders")
                .put("HttpMethod", "PUT")
                .put("InvocationRateLimitPerSecond", 20)
                .put("Description", "Updated destination");

        provisioner.provision(r, props, ctx("orders-dest"));

        verify(events).updateApiDestination(eq("orders-dest"), any(), any(), any(), any(), any(), eq(REGION));
        assertEquals("orders-dest", r.getPhysicalId());
    }

    private static ApiDestination priorApiDestination() {
        return new ApiDestination(
                "orders-dest",
                "arn:aws:events:us-east-1:000000000000:api-destination/orders-dest/12345678",
                "arn:aws:events:us-east-1:000000000000:connection/conn/111",
                "https://api.example.com/v1/orders",
                "POST",
                null,
                "Original destination");
    }

    private StackResource updatedApiDestination() {
        when(events.describeApiDestination("orders-dest", REGION)).thenReturn(priorApiDestination());
        when(events.updateApiDestination(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(priorApiDestination());
        StackResource r = resource("AWS::Events::ApiDestination", "MyDestination");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "orders-dest")
                .put("ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/conn/222")
                .put("InvocationEndpoint", "https://api.example.com/v2/orders")
                .put("HttpMethod", "PUT")
                .put("InvocationRateLimitPerSecond", 20);
        provisioner.provision(r, props, ctx("orders-dest"));
        return r;
    }

    @Test
    void aRollbackPutsBackTheApiDestinationTheUpdateChanged() {
        StackResource r = updatedApiDestination();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(events).restoreApiDestination("orders-dest", "Original destination",
                "arn:aws:events:us-east-1:000000000000:connection/conn/111",
                "https://api.example.com/v1/orders", "POST", null, REGION);
        assertFalse(provisioner.rollbackUpdate(r), "the snapshot is spent once restored");
    }

    @Test
    void anApiDestinationCreateTakesNoSnapshotToRollBack() {
        StackResource r = resource("AWS::Events::ApiDestination", "MyDestination");
        when(events.createApiDestination(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(priorApiDestination());
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "orders-dest")
                .put("ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/conn/111")
                .put("InvocationEndpoint", "https://api.example.com/v1/orders")
                .put("HttpMethod", "POST");
        provisioner.provision(r, props, ctx(null));

        assertFalse(provisioner.rollbackUpdate(r));
        verify(events, never()).restoreApiDestination(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRuleTargetKeepsItsHttpParameters() {
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString())).thenReturn(List.of());
        ObjectNode destinationTarget = target("dest",
                "arn:aws:events:us-east-1:000000000000:api-destination/orders-dest/12345678");
        ObjectNode httpParameters = destinationTarget.putObject("HttpParameters");
        httpParameters.putArray("PathParameterValues").add("orders");
        httpParameters.putObject("HeaderParameters").put("x-tenant", "alpha");
        httpParameters.putObject("QueryStringParameters").put("version", "v1");
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(destinationTarget);

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx("orders"));

        ArgumentCaptor<List<Target>> put = ArgumentCaptor.forClass(List.class);
        verify(events).putTargets(eq("orders"), any(), put.capture(), anyString());
        HttpParameters stored = put.getValue().get(0).getHttpParameters();
        assertEquals(List.of("orders"), stored.getPathParameterValues());
        assertEquals("alpha", stored.getHeaderParameters().get("x-tenant"));
        assertEquals("v1", stored.getQueryStringParameters().get("version"));
    }

    @Test
    void provisionApiDestinationRejectsNameChangeOnUpdate() {
        StackResource r = resource("AWS::Events::ApiDestination", "MyDestination");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "new-name")
                .put("ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/conn/111")
                .put("InvocationEndpoint", "https://api.example.com/orders")
                .put("HttpMethod", "POST");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx("old-name")));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("requires resource replacement"));
    }

    @Test
    void deleteApiDestinationCallsService() {
        provisioner.delete("AWS::Events::ApiDestination", "orders-dest", REGION);
        verify(events).deleteApiDestination("orders-dest", REGION);
    }

    @Test
    void deleteApiDestinationIgnoresAlreadyDeleted() {
        doThrow(new AwsException("ResourceNotFoundException", "Not found", 400))
                .when(events).deleteApiDestination("orders-dest", REGION);

        provisioner.delete("AWS::Events::ApiDestination", "orders-dest", REGION);
        verify(events).deleteApiDestination("orders-dest", REGION);
    }
}
