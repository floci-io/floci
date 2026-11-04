package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.EventsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.AppSyncParameters;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.RuleState;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class EventBridgeAppSyncTargetSettingsIntegrationTest {

    private static final String ACCOUNT = "725018800001";
    private static final String ENDPOINT = "arn:aws:appsync:us-east-1:" + ACCOUNT + ":endpoints/graphql-api/example";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/events-appsync";
    private static final String OPERATION = "mutation Publish($id: ID!) { publish(id: $id) { id } }";
    @InjectMock
    GraphqlSidecarClient sidecar;
    @Inject
    EventsCfnProvisioner provisioner;
    private EventBridgeClient client;
    private String rule;

    @BeforeEach
    void createRule() {
        reset(sidecar);
        when(sidecar.plan(any(), any(), any(), any())).thenReturn(new PlanResult("MUTATION", List.of()));
        client = client(ACCOUNT);
        rule = "appsync-settings-" + UUID.randomUUID();
        client.putRule(request -> request.name(rule).eventPattern("{}").state(RuleState.DISABLED));
    }

    @AfterEach
    void deleteRule() {
        try {
            client.removeTargets(request -> request.rule(rule).ids("appsync", "bad"));
            client.deleteRule(request -> request.name(rule));
        } finally {
            client.close();
        }
    }

    @Test
    void theModelMaximumGraphqlOperationLengthIsAccepted() {
        String operation = OPERATION + "#" + "x".repeat(1048576 - OPERATION.length() - 1);
        putTarget(operation);
        assertEquals(operation, listedTarget().appSyncParameters().graphQLOperation());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1048577})
    void invalidOperationLengthsRejectTheRequestWithoutEchoingSensitiveContent(int length) {
        putTarget(OPERATION);
        String value = length == 0 ? "" : "SENSITIVE-MUTATION-" + "x".repeat(length - "SENSITIVE-MUTATION-".length());
        EventBridgeException error = assertThrows(EventBridgeException.class, () -> client.putTargets(request -> request.rule(rule)
                .targets(Target.builder().id("appsync").arn(ENDPOINT).roleArn(ROLE)
                                .appSyncParameters(p -> p.graphQLOperation("mutation { replacement }")).build(),
                        Target.builder().id("bad").arn(ENDPOINT).roleArn(ROLE)
                                .appSyncParameters(p -> p.graphQLOperation(value)).build())));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertTrue(error.getMessage().contains("targets.2.member.appSyncParameters.graphQLOperation"));
        assertFalse(error.getMessage().contains("SENSITIVE-MUTATION-"));
        assertEquals(OPERATION, listedTarget().appSyncParameters().graphQLOperation());
        assertEquals(1, client.listTargetsByRule(request -> request.rule(rule)).targets().size());
    }

    @Test
    void sdkRoundTripsTheGraphqlOperationAndTargetRole() {
        putTarget(OPERATION);
        Target target = listedTarget();
        assertNotNull(target.appSyncParameters());
        assertEquals(OPERATION, target.appSyncParameters().graphQLOperation());
        assertEquals(ROLE, target.roleArn());
        assertEquals(ENDPOINT, target.arn());
    }

    @Test
    void replacementUpdatesOrRemovesAppSyncParameters() {
        putTarget(OPERATION);
        String replacement = "mutation { publish(id: \"replacement\") { id } }";
        putTarget(replacement);
        assertNotNull(listedTarget().appSyncParameters());
        assertEquals(replacement, listedTarget().appSyncParameters().graphQLOperation());
        client.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                .arn("arn:aws:sqs:us-east-1:" + ACCOUNT + ":queue").roleArn(ROLE).build()));
        assertNull(listedTarget().appSyncParameters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "malformed", "query", "two mutations"})
    void invalidAppSyncSettingsCannotReplaceStoredTargets(String scenario) {
        putTarget(OPERATION);
        Target original = listedTarget();
        Target.Builder invalid = original.toBuilder().appSyncParameters((AppSyncParameters) null);
        if ("empty".equals(scenario)) {
            invalid.appSyncParameters(AppSyncParameters.builder().build());
        } else if (!"missing".equals(scenario)) {
            String operation = switch (scenario) {
                case "malformed" -> "mutation SENSITIVE {";
                case "query" -> "query SENSITIVE { publish { id } }";
                default -> "mutation SENSITIVE { first } mutation Other { second }";
            };
            invalid.appSyncParameters(p -> p.graphQLOperation(operation));
            if ("two mutations".equals(scenario)) {
                when(sidecar.plan(any(), any(), eq(operation), isNull())).thenThrow(
                        new AppSyncTransportException(400, "BadRequestException", "SENSITIVE ambiguous operation"));
            } else {
                when(sidecar.plan(any(), any(), eq(operation), isNull())).thenReturn(
                        new PlanResult("query".equals(scenario) ? "QUERY" : null, List.of()));
            }
        }
        EventBridgeException error = assertThrows(EventBridgeException.class,
                () -> client.putTargets(request -> request.rule(rule).targets(
                        original.toBuilder().id("bad").build(), invalid.build())));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals(400, error.statusCode());
        assertFalse(error.getMessage().contains("SENSITIVE"));
        assertEquals(List.of(original), client.listTargetsByRule(request -> request.rule(rule)).targets());
    }

    @Test
    void parserOutageDoesNotOverwriteTargetsOrBecomeAValidationError() {
        putTarget(OPERATION);
        Target original = listedTarget();
        when(sidecar.plan(any(), any(), any(), any())).thenThrow(
                new AppSyncTransportException(500, "InternalFailureException", "SENSITIVE transport failure"));
        EventBridgeException error = assertThrows(EventBridgeException.class, () -> putTarget("mutation { replacement }"));
        assertEquals("InternalFailure", error.awsErrorDetails().errorCode());
        assertEquals(500, error.statusCode());
        assertFalse(error.getMessage().contains("SENSITIVE"));
        assertEquals(List.of(original), client.listTargetsByRule(request -> request.rule(rule)).targets());
    }

    @Test
    void cloudFormationRollbackRestoresAcceptedTargetsWhileTheParserIsUnavailable() {
        putTarget(OPERATION);
        Target original = listedTarget();
        StackResource resource = ruleResource();
        ObjectNode props = new ObjectMapper().createObjectNode().put("Name", rule).put("State", "DISABLED");
        props.putObject("EventPattern");
        props.putArray("Targets");
        RequestScopes.runAs(ACCOUNT, "us-east-1", () -> provisioner.provision(resource, props, updateContext()));
        assertTrue(client.listTargetsByRule(request -> request.rule(rule)).targets().isEmpty());

        when(sidecar.plan(any(), any(), any(), any())).thenThrow(
                new AppSyncTransportException(500, "InternalFailureException", "Parser unavailable"));
        clearInvocations(sidecar);
        RequestScopes.runAs(ACCOUNT, "us-east-1", () -> assertTrue(provisioner.rollbackUpdate(resource)));

        assertEquals(List.of(original), client.listTargetsByRule(request -> request.rule(rule)).targets());
        assertNull(resource.getAttributes().get(CfnRollback.RULE_TARGETS_SNAPSHOT_ATTR));
        verifyNoInteractions(sidecar);
    }

    @Test
    void failedCloudFormationUpdateRestoresItsSnapshotWithoutRetryingTheParser() {
        putTarget(OPERATION);
        Target original = listedTarget();
        StackResource resource = ruleResource();
        ObjectNode props = new ObjectMapper().createObjectNode().put("Name", rule).put("State", "DISABLED");
        props.putObject("EventPattern");
        props.putArray("Targets").addObject().put("Id", "appsync").put("Arn", ENDPOINT).put("RoleArn", ROLE)
                .putObject("AppSyncParameters").put("GraphQLOperation", "mutation { replacement }");
        when(sidecar.plan(any(), any(), any(), any())).thenThrow(
                new AppSyncTransportException(500, "InternalFailureException", "Parser unavailable"));
        clearInvocations(sidecar);
        RequestScopes.runAs(ACCOUNT, "us-east-1", () -> assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, updateContext())));

        assertEquals(List.of(original), client.listTargetsByRule(request -> request.rule(rule)).targets());
        assertNull(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertNull(resource.getAttributes().get(CfnRollback.RULE_TARGETS_SNAPSHOT_ATTR));
        verify(sidecar, times(1)).plan(any(), any(), any(), any());
    }

    private StackResource ruleResource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Events::Rule");
        resource.setLogicalId("Rule");
        resource.setPhysicalId(rule);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext updateContext() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.omitNoValue(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", ACCOUNT, "rollback-stack", rule);
    }

    @Test
    void identicalRuleNamesInAnotherAccountCannotReplaceTheOperation() {
        putTarget(OPERATION);
        try (EventBridgeClient other = client("725018800002")) {
            other.putRule(request -> request.name(rule).eventPattern("{}").state(RuleState.DISABLED));
            try {
                other.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                        .arn(ENDPOINT).appSyncParameters(p -> p.graphQLOperation("mutation { other }")).build()));
                assertNotNull(listedTarget().appSyncParameters());
                assertEquals(OPERATION, listedTarget().appSyncParameters().graphQLOperation());
                assertEquals("mutation { other }", other.listTargetsByRule(request -> request.rule(rule))
                        .targets().getFirst().appSyncParameters().graphQLOperation());
            } finally {
                other.removeTargets(request -> request.rule(rule).ids("appsync"));
                other.deleteRule(request -> request.name(rule));
            }
        }
    }

    @Test
    void storedTargetSerializationRetainsTheExactOperation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String stored = "{\"id\":\"appsync\",\"arn\":\"" + ENDPOINT
                + "\",\"appSyncParameters\":{\"GraphQLOperation\":\"mutation { publish { id } }\"}}";
        // The persisted model and the SDK wire model both have the name Target.
        io.github.hectorvent.floci.services.eventbridge.model.Target target = mapper.readValue(stored,
                io.github.hectorvent.floci.services.eventbridge.model.Target.class);
        JsonNode restored = mapper.readTree(mapper.writeValueAsBytes(target));
        assertEquals("mutation { publish { id } }", restored.path("appSyncParameters").path("GraphQLOperation").asText());
    }

    private void putTarget(String operation) {
        assertEquals(0, client.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                .arn(ENDPOINT).roleArn(ROLE).appSyncParameters(p -> p.graphQLOperation(operation)).build())).failedEntryCount());
    }

    private Target listedTarget() {
        return client.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
    }

    private static EventBridgeClient client(String account) {
        return AwsTestClients.create(EventBridgeClient.builder(), account, Region.US_EAST_1);
    }
}
