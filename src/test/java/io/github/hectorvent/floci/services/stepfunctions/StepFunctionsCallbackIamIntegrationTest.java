package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StepFunctionsCallbackIamIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource
    URI endpoint;

    @Inject
    IamService iam;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({
            "integration,exact,true", "integration,wrong,false", "integration,wildcard,true",
            "integration,deny-exact,false", "integration,deny-wrong,true",
            "integration,deny-all,false", "integration,deny-other-type,true", "integration,execution,false",
            "integration-version,exact,true", "integration-version,wrong,false",
            "integration-version,deny-exact,false",
            "integration-version,qualified,false", "integration-version,deny-qualified,true",
            "activity,exact,true", "activity,wrong,false", "activity,wildcard,true",
            "activity,deny-exact,false", "activity,deny-wrong,true",
            "activity,deny-all,false", "activity,deny-other-type,true", "activity,execution,false",
            "activity,state-machine,false"
    })
    void signedCallbacksAuthorizeTheTokensResource(String mode, String policy, boolean allowed) throws Exception {
        SfnClient admin = client("test", "test");
        String name = "callback-iam-" + UUID.randomUUID();
        String role = executionRole(name);
        SqsTestQueue queue = SqsTestQueue.create(sqsService, "000000000000", "us-east-1", "callback-iam-");
        cleanup.register(queue::delete);
        String activity = admin.createActivity(request -> request.name(name)).activityArn();
        cleanup.register(() -> admin.deleteActivity(request -> request.activityArn(activity)));
        boolean isActivity = "activity".equals(mode);
        Map<String, Object> task = isActivity
                ? Map.of("Type", "Task", "Resource", activity, "TimeoutSeconds", 60, "End", true)
                : Map.of("Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage.waitForTaskToken",
                        "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", Map.of("token.$", "$$.Task.Token")),
                        "TimeoutSeconds", 60, "End", true);
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Callback", "States", Map.of("Callback", task)));
        boolean versioned = "integration-version".equals(mode);
        CreateStateMachineResponse created = admin.createStateMachine(request -> request.name(name).roleArn(role)
                .publish(versioned).definition(definition));
        String machine = created.stateMachineArn();
        String startMachine = versioned ? created.stateMachineVersionArn() : machine;
        cleanup.register(() -> admin.deleteStateMachine(request -> request.stateMachineArn(machine)));
        String resource = isActivity ? activity : machine;
        String policyResource = switch (policy) {
            case "wildcard", "deny-all" -> "*";
            case "wrong", "deny-wrong" -> resource + "-other";
            case "execution" -> machine.replace(":stateMachine:", ":execution:") + ":*";
            case "state-machine" -> machine;
            case "deny-other-type" -> (isActivity ? machine : activity).replace(name, "*");
            case "qualified", "deny-qualified" -> startMachine;
            default -> resource;
        };
        List<Map<String, Object>> statements = policy.startsWith("deny-")
                ? List.of(statement("Allow", "deny-other-type".equals(policy) ? resource : "*"),
                        statement("Deny", policyResource))
                : List.of(statement("Allow", policyResource));
        SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "callback-", "callback",
                MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)));
        cleanup.register(() -> {
            try {
                user.close();
            } catch (Exception error) {
                throw new AssertionError("callback user cleanup failed", error);
            }
        });
        SfnClient actor = client(user.accessKeyId(), user.secretAccessKey());

        for (String action : List.of("heartbeat", "success", "failure")) {
            SfnException invalid = assertThrows(SfnException.class, () -> callback(actor, action, "invalid-token"));
            assertEquals("InvalidToken", invalid.awsErrorDetails().errorCode());
            String execution = admin.startExecution(request -> request.stateMachineArn(startMachine)).executionArn();
            cleanup.register(() -> {
                if ("RUNNING".equals(admin.describeExecution(request -> request.executionArn(execution)).statusAsString())) {
                    admin.stopExecution(request -> request.executionArn(execution));
                }
            });
            String token = isActivity
                    ? admin.getActivityTask(request -> request.activityArn(activity)).taskToken()
                    : queue.awaitJson(Duration.ofSeconds(10)).path("token").asText();
            if (allowed) {
                callback(actor, action, token);
                if (!"heartbeat".equals(action)) {
                    assertEquals("success".equals(action) ? "SUCCEEDED" : "FAILED", terminal(admin, execution));
                    SfnException closed = assertThrows(SfnException.class, () -> callback(actor, action, token));
                    assertEquals("TaskTimedOut", closed.awsErrorDetails().errorCode());
                }
            } else {
                SfnException denied = assertThrows(SfnException.class, () -> callback(actor, action, token));
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                assertEquals("RUNNING", admin.describeExecution(request -> request.executionArn(execution)).statusAsString());
                admin.sendTaskSuccess(request -> request.taskToken(token).output("{}"));
                assertEquals("SUCCEEDED", terminal(admin, execution), "denial must not consume the token");
            }
            if ("RUNNING".equals(admin.describeExecution(request -> request.executionArn(execution)).statusAsString())) {
                admin.stopExecution(request -> request.executionArn(execution));
            }
        }
    }

    @Test
    void signedNamedResourcesDoNotUseTheCallbackTokenSelector() throws Exception {
        SfnClient admin = client("test", "test");
        String name = "named-" + UUID.randomUUID();
        String role = executionRole(name);
        String prefix = "arn:aws:states:us-east-1:000000000000:";
        List<Map<String, Object>> statements = List.of(
                Map.of("Effect", "Allow", "Action", "states:*", "Resource",
                        List.of(prefix + "activity:" + name, prefix + "stateMachine:" + name)),
                Map.of("Effect", "Allow", "Action", "iam:PassRole", "Resource", role));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "named-", "named",
                MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)))) {
            SfnClient actor = client(user.accessKeyId(), user.secretAccessKey());
            String activity = actor.createActivity(request -> request.name(name)).activityArn();
            cleanup.register(() -> admin.deleteActivity(request -> request.activityArn(activity)));
            assertEquals(name, actor.describeActivity(request -> request.activityArn(activity)).name());
            String definition = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}";
            String machine = actor.createStateMachine(request -> request.name(name).roleArn(role)
                    .definition(definition)).stateMachineArn();
            cleanup.register(() -> admin.deleteStateMachine(request -> request.stateMachineArn(machine)));
            assertEquals(name, actor.describeStateMachine(request -> request.stateMachineArn(machine)).name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"taskToken\":null}", "{\"taskToken\":\"\"}",
            "{\"taskToken\":\" \"}", "{\"taskToken\":42}", "{\"taskToken\":true}",
            "{\"taskToken\":{}}", "{\"taskToken\":[]}"})
    void invalidOperandsKeepTokenErrorPrecedence(String body) throws Exception {
        for (String mode : List.of("none", "deny", "allow")) {
            Map<String, Object> policy = Map.of("Effect", "deny".equals(mode) ? "Deny" : "Allow", "Resource", "*", "Action",
                    "none".equals(mode) ? "states:DescribeExecution" : "states:*");
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "operand-", "callback",
                    MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(policy))))) {
                for (String operation : List.of("SendTaskHeartbeat", "SendTaskSuccess", "SendTaskFailure")) {
                    Response response = given().filter(AwsRequestSigner.signedAs(
                                    user.accessKeyId(), user.secretAccessKey(), "states"))
                            .contentType("application/x-amz-json-1.0")
                            .header("X-Amz-Target", "AWSStepFunctions." + operation)
                            .body(body.getBytes(StandardCharsets.UTF_8)).post(endpoint);
                    assertEquals(400, response.statusCode(), operation + ": " + response.asString());
                    assertTrue(response.jsonPath().getString("__type").endsWith("InvalidToken"),
                            operation + ": " + response.asString());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "wrong", "wildcard"})
    void foreignCallbacksDoNotRevealTheTokensResource(String policy) throws Exception {
        SfnClient owner = client("test", "test");
        String name = "foreign-callback-" + UUID.randomUUID();
        String role = executionRole(name);
        String activity = owner.createActivity(request -> request.name(name)).activityArn();
        cleanup.register(() -> owner.deleteActivity(request -> request.activityArn(activity)));
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Callback", "States", Map.of("Callback",
                Map.of("Type", "Task", "Resource", activity, "TimeoutSeconds", 60, "End", true))));
        String machine = owner.createStateMachine(request -> request.name(name).roleArn(role)
                .definition(definition)).stateMachineArn();
        cleanup.register(() -> owner.deleteStateMachine(request -> request.stateMachineArn(machine)));
        String resource = switch (policy) {
            case "matching" -> activity;
            case "wrong" -> activity + "-other";
            default -> "*";
        };
        AccessKey key = foreignUser(name, MAPPER.writeValueAsString(Map.of("Version", "2012-10-17",
                "Statement", List.of(statement("Allow", resource)))));
        SfnClient foreign = client(key.getAccessKeyId(), key.getSecretAccessKey());
        String execution = owner.startExecution(request -> request.stateMachineArn(machine)).executionArn();
        cleanup.register(() -> {
            if ("RUNNING".equals(owner.describeExecution(request -> request.executionArn(execution)).statusAsString())) {
                owner.stopExecution(request -> request.executionArn(execution));
            }
        });
        String token = owner.getActivityTask(request -> request.activityArn(activity)).taskToken();
        for (boolean completed : List.of(false, true)) {
            if (completed) {
                owner.sendTaskSuccess(request -> request.taskToken(token).output("{}"));
                assertEquals("SUCCEEDED", terminal(owner, execution));
            }
            for (String action : List.of("heartbeat", "success", "failure")) {
                SfnException denied = assertThrows(SfnException.class, () -> callback(foreign, action, token));
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                assertEquals("Not authorized to access this resource.", denied.awsErrorDetails().errorMessage());
            }
        }
    }

    private AccessKey foreignUser(String name, String policy) {
        String account = "111111111111";
        RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
        RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "callback", policy));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "callback")));
        AccessKey key = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteAccessKey(name, key.getAccessKeyId())));
        return key;
    }

    private SfnClient client(String access, String secret) {
        SfnClient client = SfnClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
        cleanup.register(client::close);
        return client;
    }

    private String executionRole(String name) {
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.register(iam::close);
        String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                 "Principal":{"Service":"states.amazonaws.com"}}]}""")).role().arn();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(name)));
        iam.putRolePolicy(request -> request.roleName(name).policyName("queue").policyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"}]}"""));
        cleanup.register(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("queue")));
        return role;
    }

    private static Map<String, Object> statement(String effect, String resource) {
        return Map.of("Effect", effect, "Resource", resource,
                "Action", List.of("states:SendTaskHeartbeat", "states:SendTaskSuccess", "states:SendTaskFailure"));
    }

    private static void callback(SfnClient actor, String action, String token) {
        switch (action) {
            case "heartbeat" -> actor.sendTaskHeartbeat(request -> request.taskToken(token));
            case "success" -> actor.sendTaskSuccess(request -> request.taskToken(token).output("{}"));
            case "failure" -> actor.sendTaskFailure(request -> request.taskToken(token).error("ExpectedFailure"));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static String terminal(SfnClient admin, String execution) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String status = admin.describeExecution(request -> request.executionArn(execution)).statusAsString();
            if (!"RUNNING".equals(status)) {
                return status;
            }
            Thread.sleep(10);
        }
        return fail("callback did not finish its execution");
    }
}
