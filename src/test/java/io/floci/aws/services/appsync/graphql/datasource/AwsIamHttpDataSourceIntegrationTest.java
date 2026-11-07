package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.appsync.model.DataSourceType;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.scheduler.SchedulerService;
import io.floci.aws.services.stepfunctions.StepFunctionsService;
import io.floci.aws.testing.AppSyncHttpTestPolicies;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(AwsIamHttpDataSourceIntegrationTest.EnforcedSignatures.class)
class AwsIamHttpDataSourceIntegrationTest {

    private static final String ACCOUNT = "724600001234";
    private static final String API_REGION = "us-east-1";
    private static final String TARGET_REGION = "us-west-2";
    private static final String API_ARN = "arn:aws:appsync:" + API_REGION + ":" + ACCOUNT + ":apis/example";

    public static class EnforcedSignatures implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true", "floci.auth.validate-signatures", "true");
        }
    }

    @Inject
    AppSyncDataSourceInvokers invokers;
    @InjectSpy
    IamService iam;
    @Inject
    SchedulerService scheduler;
    @Inject
    StepFunctionsService states;

    private String roleName;
    private DataSource source;

    @BeforeEach
    void createRole() {
        roleName = "http-data-source-" + UUID.randomUUID();
        String roleArn = RequestScopes.callAs(ACCOUNT, API_REGION, () -> {
            String arn = iam.createRole(roleName, "/appsync/", AppSyncHttpTestPolicies.trust(API_ARN, ACCOUNT), null, 3600, Map.of()).getArn();
            iam.putRolePolicy(roleName, "schedule", policy("Allow"));
            return arn;
        });
        source = new DataSource();
        source.setType(DataSourceType.HTTP);
        source.setName("Scheduler");
        source.setDataSourceArn(API_ARN + "/datasources/Scheduler");
        source.setServiceRoleArn(roleArn);
        source.setHttpConfig(Map.of("endpoint", "https://scheduler." + TARGET_REGION + ".amazonaws.com",
                "authorizationConfig", Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", TARGET_REGION, "signingServiceName", "scheduler"))));
        clearInvocations(iam);
    }

    @AfterEach
    void deleteRole() {
        RequestScopes.runAs(ACCOUNT, API_REGION, () -> {
            iam.deleteRolePolicy(roleName, "schedule");
            iam.deleteRole(roleName);
        });
    }

    @Test
    void signedSchedulerRequestReachesTheLocalServiceInTheConfiguredRegionAndAccount() {
        Map<?, ?> result = invoke();
        assertEquals(404, result.get("statusCode"));
        assertTrue(result.get("body").toString().contains("ResourceNotFoundException"));
        assertSessionWasRemoved();
    }

    @Test
    void targetServiceEnforcesTheRealRolePolicyAndStillCleansUpTheSession() {
        RequestScopes.runAs(ACCOUNT, API_REGION, () -> iam.putRolePolicy(roleName, "schedule", policy("Deny")));
        Map<?, ?> result = invoke();
        assertEquals(403, result.get("statusCode"));
        assertSessionWasRemoved();
    }

    @Test
    void cleanedUpSessionCannotBeReplayedAgainstTheNativeListener() throws Exception {
        assertEquals(404, invoke().get("statusCode"));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), secret.capture(), token.capture(),
                eq(source.getServiceRoleArn()), any(Instant.class), isNull());
        URI uri = URI.create("http://localhost:" + RestAssured.port + "/schedules/missing");
        Map<String, String> headers = AwsRequestSigner.signedAs(key.getValue(), secret.getValue(), "scheduler")
                .inRegion(TARGET_REGION).headersFor("GET", uri,
                        Map.of("x-amz-security-token", token.getValue()), new byte[0]);
        given().headers(headers).header("x-amz-security-token", token.getValue())
                .when().get("/schedules/missing")
                .then().statusCode(403).body(containsString("UnrecognizedClientException"));
    }

    @Test
    void signedSchedulerCreatesReadsAndDeletesOnlyInItsTargetAccountAndRegion() {
        String name = "http-" + UUID.randomUUID();
        String executionRole = roleName + "-execution";
        String scheduleArn = "arn:aws:scheduler:" + TARGET_REGION + ":" + ACCOUNT + ":schedule/default/" + name;
        String executionArn = RequestScopes.callAs(ACCOUNT, API_REGION, () -> iam.createRole(executionRole, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":"scheduler.amazonaws.com"}}]}
                """, null, 3600, Map.of()).getArn());
        try {
            RequestScopes.runAs(ACCOUNT, API_REGION, () -> iam.putRolePolicy(roleName, "schedule", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":["scheduler:CreateSchedule","scheduler:GetSchedule","scheduler:DeleteSchedule"],
                    "Resource":"%s"},{"Effect":"Allow","Action":"iam:PassRole","Resource":"%s"}]}
                    """.formatted(scheduleArn, executionArn)));
            Map<String, Object> body = Map.of("ScheduleExpression", "at(2099-01-01T00:00:00)", "State", "DISABLED",
                    "FlexibleTimeWindow", Map.of("Mode", "OFF"), "Target", Map.of("RoleArn", executionArn,
                            "Arn", "arn:aws:lambda:" + TARGET_REGION + ":" + ACCOUNT + ":function:unused"));
            assertEquals(200, invoke("POST", "/schedules/" + name, Map.of("body", body)).get("statusCode"));
            Map<?, ?> read = invoke("GET", "/schedules/" + name, Map.of());
            assertEquals(200, read.get("statusCode"));
            assertTrue(read.get("body").toString().contains(scheduleArn));
            assertTrue(RequestScopes.callAs(ACCOUNT, API_REGION,
                    () -> scheduler.listSchedules(null, name, null, API_REGION)).isEmpty());
            assertTrue(RequestScopes.callAs("724699999999", TARGET_REGION,
                    () -> scheduler.listSchedules(null, name, null, TARGET_REGION)).isEmpty());
            assertEquals(200, invoke("DELETE", "/schedules/" + name, Map.of()).get("statusCode"));
            assertEquals(404, invoke("GET", "/schedules/" + name, Map.of()).get("statusCode"));
            assertSessionWasRemoved();
        } finally {
            scheduler.deleteScheduleForAccount(ACCOUNT, name, "default", TARGET_REGION);
            RequestScopes.runAs(ACCOUNT, API_REGION, () -> iam.deleteRole(executionRole));
        }
    }

    @Test
    void aRoleWithoutCreateSchedulePermissionCannotStoreASchedule() {
        String name = "denied-" + UUID.randomUUID();
        Map<?, ?> denied = invoke("POST", "/schedules/" + name, Map.of("body", Map.of()));
        assertEquals(403, denied.get("statusCode"));
        assertTrue(RequestScopes.callAs(ACCOUNT, TARGET_REGION,
                () -> scheduler.listSchedules(null, name, null, TARGET_REGION)).isEmpty());
        assertSessionWasRemoved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendTaskSuccess", "SendTaskFailure"})
    void signedCallbacksRespectRoleDenialBeforeCompletingTheOwnedToken(String operation) {
        String token = "http-" + UUID.randomUUID();
        String resource = "arn:aws:states:" + TARGET_REGION + ":" + ACCOUNT + ":stateMachine:callback";
        CompletableFuture<JsonNode> future = states.registerPendingToken(token, ACCOUNT, resource);
        configureStates();
        try {
            callbackPolicy("Deny", operation, resource);
            Map<?, ?> denied = callback(operation, token);
            assertEquals(400, denied.get("statusCode"));
            assertTrue(denied.get("body").toString().contains("AccessDeniedException"));
            assertFalse(future.isDone());
            callbackPolicy("Allow", operation, resource);
            assertEquals(200, callback(operation, token).get("statusCode"));
            assertTrue(future.isDone());
            if ("SendTaskSuccess".equals(operation)) {
                assertTrue(future.join().path("delivered").asBoolean());
            } else {
                assertTrue(future.isCompletedExceptionally());
            }
            assertSessionWasRemoved();
        } finally {
            states.discardPendingToken(token);
        }
    }

    @Test
    void aSignedCallbackCannotClaimATokenInAnotherRegion() {
        String token = "http-" + UUID.randomUUID();
        String resource = "arn:aws:states:" + API_REGION + ":" + ACCOUNT + ":stateMachine:callback";
        CompletableFuture<JsonNode> future = states.registerPendingToken(token, ACCOUNT, resource);
        configureStates();
        try {
            callbackPolicy("Allow", "SendTaskSuccess", "*");
            Map<?, ?> result = callback("SendTaskSuccess", token);
            assertEquals(400, result.get("statusCode"));
            assertTrue(result.get("body").toString().contains("InvalidToken"));
            assertFalse(future.isDone());
            assertSessionWasRemoved();
        } finally {
            states.discardPendingToken(token);
        }
    }

    private void configureStates() {
        source.setHttpConfig(Map.of("endpoint", "https://states." + TARGET_REGION + ".amazonaws.com",
                "authorizationConfig", Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", TARGET_REGION, "signingServiceName", "states"))));
    }

    private void callbackPolicy(String effect, String operation, String resource) {
        RequestScopes.runAs(ACCOUNT, API_REGION, () -> iam.putRolePolicy(roleName, "schedule", """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Action":"states:%s","Resource":"%s"}]}
                """.formatted(effect, operation, resource)));
    }

    private Map<?, ?> callback(String operation, String token) {
        return invoke("POST", "/", Map.of("headers", Map.of("X-Amz-Target", "AWSStepFunctions." + operation),
                "body", Map.of("taskToken", token, "output", "{\"delivered\":true}", "error", "TaskFailed", "cause", "test")));
    }

    private Map<?, ?> invoke() {
        return invoke("GET", "/schedules/missing", Map.of("headers", Map.of("Content-Type", "application/json")));
    }

    private Map<?, ?> invoke(String method, String path, Map<String, Object> params) {
        return (Map<?, ?>) RequestScopes.callAs(ACCOUNT, API_REGION, () -> invokers.invoke(source,
                Map.of("method", method, "resourcePath", path, "params", params), API_REGION));
    }

    private void assertSessionWasRemoved() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(iam, atLeastOnce()).registerSessionForAccount(eq(ACCOUNT), key.capture(), anyString(), anyString(),
                eq(source.getServiceRoleArn()), any(Instant.class), isNull());
        for (String issued : key.getAllValues()) {
            assertFalse(iam.isKnownAccessKey(issued));
        }
    }

    private static String policy(String effect) {
        return AppSyncHttpTestPolicies.getSchedule(effect, TARGET_REGION, ACCOUNT);
    }
}
