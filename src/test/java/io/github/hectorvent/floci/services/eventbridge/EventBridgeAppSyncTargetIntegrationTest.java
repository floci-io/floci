package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlanResult;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedDirective;
import io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient.PlannedField;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.ResolverCallbackSessions;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.eventbridge.EventBridgeAppSyncFixtures.policy;
import static io.github.hectorvent.floci.services.eventbridge.EventBridgeAppSyncFixtures.trust;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeAppSyncTargetIntegrationTest {

    private static final String ACCOUNT = "725018800005";
    private static final String REGION = "us-east-1";
    private static final String RULE = "arn:aws:events:" + REGION + ":" + ACCOUNT + ":rule/publish";
    private static final String OPERATION = "mutation Publish($id: ID!) { publish(id: $id) { id } }";

    @Inject
    EventBridgeInvoker invoker;
    @Inject
    AppSyncService appSync;
    @Inject
    SchemaRegistry schemas;
    @Inject
    IamService iam;
    @Inject
    ResolverCallbackSessions sessions;
    @InjectMock
    GraphqlSidecarClient sidecar;
    @InjectMock
    AppSyncResolverExecutor resolvers;

    private GraphqlApi api;
    private String roleName;
    private String roleArn;
    private Target target;
    private final AtomicReference<Map<String, Object>> variables = new AtomicReference<>();
    private final AtomicReference<Object> identity = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        reset(sidecar, resolvers);
        variables.set(null);
        identity.set(null);
        api = EventBridgeAppSyncFixtures.createApi(appSync, schemas, ACCOUNT, REGION,
                "type Query { unused: String } type Mutation { publish(id: ID!): Result } type Result { id: ID }");
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            roleName = "appsync-target-" + UUID.randomUUID();
            roleArn = iam.createRole(roleName, "/", trust("events", RULE, ACCOUNT), null, 3600, Map.of()).getArn();
            iam.putRolePolicy(roleName, "invoke", policy("Allow", api.getArn() + "/*"));
        });
        target = EventBridgeAppSyncFixtures.target(api, ACCOUNT, REGION, roleArn, OPERATION);
        EventBridgeAppSyncFixtures.stubMutation(sidecar, resolvers,
                new PlannedField("Mutation", "publish", List.of(), List.of()),
                new PlannedField("Result", "id", List.of(), List.of()));
        lenient().when(sidecar.execute(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            variables.set(call.getArgument(3));
            GraphqlSidecarClient.ResolveSpec spec = call.getArgument(6);
            ResolverCallbackSessions.Session session = sessions.find(spec.token()).orElseThrow();
            identity.set(session.identity());
            assertEquals(ACCOUNT, session.accountId());
            assertEquals(REGION, session.region());
            return Map.of("data", Map.of("publish", Map.of("id", "delivered")));
        });
    }

    @AfterEach
    void cleanUp() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            appSync.deleteGraphqlApi(api.getApiId());
            iam.deleteRolePolicy(roleName, "invoke");
            iam.deleteRole(roleName);
        });
        reset(sidecar, resolvers);
    }

    @Test
    void transformedInputRunsAMutationAsTheTargetRole() {
        target.setInputPath("$.detail");
        invoke("{\"detail\":{\"id\":\"event-1\",\"optional\":null}}");
        assertNotNull(variables.get());
        assertEquals("event-1", variables.get().get("id"));
        assertEquals(true, variables.get().containsKey("optional"));
        assertNotNull(identity.get());
        Map<?, ?> caller = (Map<?, ?>) identity.get();
        assertEquals(ACCOUNT, caller.get("accountId"));
        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + roleName + "/EventBridge", caller.get("userArn"));
    }

    @Test
    void aRuleInAnotherPartitionCannotUseTheApiEvenWithAnUnconditionalTrustPolicy() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> iam.updateAssumeRolePolicy(roleName, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":"events.amazonaws.com"}}]}"""));
        AwsException error = assertThrows(AwsException.class, () -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> invoker.invokeTarget(target, "{}", REGION,
                        "arn:aws-cn:events:cn-north-1:" + ACCOUNT + ":rule/publish")));
        assertEquals("AccessDeniedException", error.getErrorCode());
        assertEquals(null, variables.get());
    }

    @Test
    void anImplicitFieldPermissionDenialCannotExecuteTheMutation() {
        RequestScopes.runAs(ACCOUNT, REGION,
                () -> iam.putRolePolicy(roleName, "invoke", policy("Allow", api.getArn() + "/unrelated/*")));
        assertRefused("AccessDeniedException");
    }

    @Test
    void additionalIamAuthorizationRequiresIamDirectivesOnTheSelectedFields() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> appSync.updateGraphqlApi(api.getApiId(),
                Map.of("name", api.getName(), "authenticationType", "API_KEY", "additionalAuthenticationProviders",
                        List.of(Map.of("authenticationType", "AWS_IAM"))), REGION));
        assertRefused("AccessDeniedException");
        PlannedDirective iamDirective = new PlannedDirective("aws_iam", Map.of());
        lenient().when(sidecar.plan(any(), any(), any(), any())).thenReturn(new PlanResult("MUTATION", List.of(
                new PlannedField("Mutation", "publish", List.of(iamDirective), List.of()),
                new PlannedField("Result", "id", List.of(), List.of(iamDirective)))));
        invoke("{\"id\":\"allowed\"}");
        assertNotNull(variables.get());
        assertEquals("allowed", variables.get().get("id"));
    }

    @Test
    void aPrivateApiIsRejectedBeforePlanning() {
        GraphqlApi privateApi = RequestScopes.callAs(ACCOUNT, REGION, () -> appSync.createGraphqlApi(
                Map.of("name", "private-" + UUID.randomUUID(), "authenticationType", "AWS_IAM", "visibility", "PRIVATE"), REGION));
        try {
            target.setArn("arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":endpoints/graphql-api/" + privateApi.getApiId());
            assertRefused("AccessDeniedException");
            verify(sidecar, never()).plan(any(), any(), any(), any());
        } finally {
            RequestScopes.runAs(ACCOUNT, REGION, () -> appSync.deleteGraphqlApi(privateApi.getApiId()));
        }
    }

    @Test
    void aRoleWithoutTheFieldPermissionCannotExecuteTheMutation() {
        RequestScopes.runAs(ACCOUNT, REGION,
                () -> iam.putRolePolicy(roleName, "invoke", policy("Deny", api.getArn() + "/*")));
        assertRefused("AccessDeniedException");
    }

    @Test
    void aTrustConditionOnAnotherRuleRefusesDelivery() {
        AwsException failure = assertThrows(AwsException.class, () -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> invoker.invokeTarget(target, "{}", REGION, RULE + "-other")));
        assertEquals(403, failure.getHttpStatus());
        assertEquals(null, variables.get());
    }

    @Test
    void aMissingRoleCannotExecuteTheMutation() {
        target.setRoleArn(null);
        assertRefused("AccessDeniedException");
    }

    @Test
    void aMissingRoleCannotBypassEnforcementWithAnEmptyFieldPlan() {
        target.setRoleArn(null);
        lenient().when(sidecar.plan(any(), any(), any(), any())).thenReturn(new PlanResult("MUTATION", List.of()));
        assertRefused("AccessDeniedException");
    }

    @Test
    void anEndpointInAnotherRegionDoesNotResolveAnApiWithTheSameId() {
        target.setArn(target.getArn().replace(REGION, "us-west-2"));
        assertRefused("NotFoundException");
    }

    @Test
    void queryOperationsCannotExecuteAsEventBridgeMutations() {
        lenient().when(sidecar.plan(any(), any(), any(), any())).thenReturn(new PlanResult("QUERY", List.of()));
        assertRefused("ValidationException");
    }

    @Test
    void anApiWithoutIamAuthorizationCannotExecuteTheMutation() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> appSync.updateGraphqlApi(api.getApiId(),
                Map.of("name", api.getName(), "authenticationType", "API_KEY"), REGION));
        assertRefused("AccessDeniedException");
    }

    @Test
    void requestValidationErrorsAreReportedAsFailedDelivery() {
        doReturn(Map.of("errors", List.of(Map.of("message", "Variable id is required", "errorType", "ValidationError"))))
                .when(sidecar).execute(any(), any(), any(), any(), any(), any(), any());
        assertRefused("ValidationException");
    }

    @Test
    void anInvalidExecutionEnvelopeIsNotAcknowledgedAsDelivery() {
        doReturn(Map.of()).when(sidecar).execute(any(), any(), any(), any(), any(), any(), any());
        assertRefused("InternalFailure");
    }

    @Test
    void anExecutionErrorWithNullDataDoesNotRetryAPossiblyCompletedMutation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", null);
        result.put("errors", List.of(Map.of("message", "output failed", "path", List.of("publish"))));
        doAnswer(call -> {
            variables.set(call.getArgument(3));
            return result;
        }).when(sidecar).execute(any(), any(), any(), any(), any(), any(), any());
        invoke("{\"id\":\"event-1\"}");
        assertNotNull(variables.get());
    }

    @Test
    void nonObjectVariablesCannotExecuteTheMutation() {
        target.setInput("[]");
        assertRefused("ValidationException");
    }

    private void invoke(String event) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> invoker.invokeTarget(target, event, REGION, RULE));
    }

    private void assertRefused(String code) {
        AwsException failure = assertThrows(AwsException.class, () -> invoke("{}"));
        assertEquals(code, failure.getErrorCode());
        assertEquals(null, variables.get());
    }

}
