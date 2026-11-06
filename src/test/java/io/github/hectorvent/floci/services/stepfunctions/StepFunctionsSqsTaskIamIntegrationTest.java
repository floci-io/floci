package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.github.hectorvent.floci.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsSqsTaskIamIntegrationTest {
    private static final String ACCOUNT = "000000000441";
    private static final String OTHER_ACCOUNT = "000000000442";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATES_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"states.amazonaws.com"}}]}
            """;
    private static final String LAMBDA_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"lambda.amazonaws.com"}}]}
            """;
    private static final String SEND = allow("sqs:SendMessage");
    private static final String READ_ONLY = allow("sqs:GetQueueUrl");
    private static final String ASSUME = allow("sts:AssumeRole");

    @TestHTTPResource
    URI baseUri;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqs:sendMessage", "aws-sdk:sqs:sendMessage"})
    void anExecutionRoleAllowedTheActionSends(String integration) throws Exception {
        SqsTestQueue queue = queue();
        String machine = machine(jsonPathTask(integration, queue, null), role(STATES_TRUST, SEND));

        Response completed = terminal(start(machine, "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @ParameterizedTest
    @CsvSource({"sqs:sendMessage,SQS.AccessDeniedException", "aws-sdk:sqs:sendMessage,Sqs.AccessDeniedException"})
    void anExecutionRoleNotAllowedTheActionIsRefused(String integration, String error) throws Exception {
        SqsTestQueue queue = queue();
        String machine = machine(jsonPathTask(integration, queue, null), role(STATES_TRUST, READ_ONLY));

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, error);
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains("sqs:SendMessage"), cause);
        assertTrue(cause.contains(queue.arn()), cause);
        assertEquals(0, queue.receive().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqs:sendMessage", "aws-sdk:sqs:sendMessage"})
    void anExecutionRoleThatDoesNotTrustStepFunctionsIsRefusedWhateverItsPolicies(String integration) throws Exception {
        SqsTestQueue queue = queue();
        String role = role(LAMBDA_TRUST, SEND);
        Response completed = terminal(start(machine(jsonPathTask(integration, queue, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertEquals(0, queue.receive().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqs:sendMessage", "aws-sdk:sqs:sendMessage"})
    void anExecutionRoleThatDoesNotExistIsRefused(String integration) throws Exception {
        SqsTestQueue queue = queue();
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();
        Response completed = terminal(start(machine(jsonPathTask(integration, queue, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertEquals(0, queue.receive().size());
    }

    @ParameterizedTest
    @CsvSource({"sqs:sendMessage,jsonpath", "aws-sdk:sqs:sendMessage,jsonpath", "sqs:sendMessage,jsonpath-path",
            "sqs:sendMessage,jsonata", "aws-sdk:sqs:sendMessage,jsonata"})
    void credentialsSendAsTheirRoleWithoutTheExecutionRolesPermission(String integration, String form) throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), SEND);
        String machine = machine(task(integration, queue, form, taskRole), executionRole);

        String execution = start(machine, MAPPER.writeValueAsString(Map.of("role", taskRole)));
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
        JsonNode scheduled = eventsOfType(execution, "TaskScheduled").getFirst();
        assertEquals(taskRole, scheduled.path("taskScheduledEventDetails").path("taskCredentials").path("roleArn").asText());
    }

    @ParameterizedTest
    @CsvSource({"sqs:sendMessage,jsonpath", "aws-sdk:sqs:sendMessage,jsonpath", "sqs:sendMessage,jsonata"})
    void aRoleTheExecutionRoleCannotAssumeFailsInTheNestedForm(String integration, String form) throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, SEND);
        String taskRole = role(LAMBDA_TRUST, SEND);
        String machine = machine(task(integration, queue, form, taskRole), executionRole);

        Response completed = terminal(start(machine, MAPPER.writeValueAsString(Map.of("role", taskRole))));

        assertFailed(completed, "States.TaskFailed");
        assertEquals("The role " + executionRole + " is not authorized to assume the task state's role, " + taskRole + ".",
                completed.jsonPath().getString("cause"));
        assertEquals(0, queue.receive().size());
    }

    @Test
    void aCredentialsRoleThatDoesNotExistFailsAsARefusedAssumption() throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, SEND);
        String taskRole = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();
        String machine = machine(jsonPathTask("sqs:sendMessage", queue, taskRole), executionRole);

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertEquals(0, queue.receive().size());
    }

    @ParameterizedTest
    @CsvSource({"sqs:sendMessage,SQS.AccessDeniedException", "aws-sdk:sqs:sendMessage,Sqs.AccessDeniedException"})
    void aCredentialsRoleNotAllowedTheActionIsRefusedEvenIfTheExecutionRoleIs(String integration, String error)
            throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, allow("sts:AssumeRole", "sqs:SendMessage"));
        String taskRole = role(trusting(executionRole), READ_ONLY);
        String machine = machine(jsonPathTask(integration, queue, taskRole), executionRole);

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, error);
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains("assumed-role/" + taskRole.substring(taskRole.lastIndexOf('/') + 1)), cause);
        assertEquals(0, queue.receive().size());
    }

    @Test
    void aForeignCredentialsRoleNeedsTheQueueToGrantIt() throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, ASSUME);
        String foreignRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "sfn-sqs-" + UUID.randomUUID(), trusting(executionRole), SEND);
        String machine = machine(jsonPathTask("sqs:sendMessage", queue, foreignRole), executionRole);

        Response refused = terminal(start(machine, "{}"));

        assertFailed(refused, "SQS.AccessDeniedException");
        String cause = refused.jsonPath().getString("cause");
        assertTrue(cause.contains("arn:aws:sts::" + OTHER_ACCOUNT + ":assumed-role/"), cause);
        assertEquals(0, queue.receive().size());

        queue.setPolicy(queuePolicy("Allow", foreignRole, queue.arn()));
        Response completed = terminal(start(machine, "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @Test
    void aQueueInAnotherAccountNeedsItsOwnGrant() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, OTHER_ACCOUNT, REGION, "sfn-sqs-iam-");
        cleanup.register(queue::delete);
        String role = role(STATES_TRUST, SEND);
        String machine = machine(jsonPathTask("sqs:sendMessage", queue, null), role);

        Response refused = terminal(start(machine, "{}"));

        assertFailed(refused, "SQS.AccessDeniedException");
        assertEquals(0, queue.receive().size());

        queue.addPermission("from-" + ACCOUNT, ACCOUNT, "SendMessage");
        Response completed = terminal(start(machine, "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @Test
    void aQueueInAnotherAccountCannotGrantWhatTheRoleLacks() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, OTHER_ACCOUNT, REGION, "sfn-sqs-iam-");
        cleanup.register(queue::delete);
        String role = role(STATES_TRUST, READ_ONLY);
        queue.setPolicy(queuePolicy("Allow", role, queue.arn()));

        Response completed = terminal(start(machine(jsonPathTask("sqs:sendMessage", queue, null), role), "{}"));

        assertFailed(completed, "SQS.AccessDeniedException");
        assertEquals(0, queue.receive().size());
    }

    @Test
    void aQueuePolicyAloneAllowsARoleOfItsOwnAccount() throws Exception {
        SqsTestQueue queue = queue();
        String role = role(STATES_TRUST, READ_ONLY);
        queue.setPolicy(queuePolicy("Allow", role, queue.arn()));

        Response completed = terminal(start(machine(jsonPathTask("sqs:sendMessage", queue, null), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @Test
    void aQueuePolicyDenyRefusesARoleAllowedTheAction() throws Exception {
        SqsTestQueue queue = queue();
        String role = role(STATES_TRUST, SEND);
        queue.setPolicy(queuePolicy("Deny", role, queue.arn()));

        Response completed = terminal(start(machine(jsonPathTask("sqs:sendMessage", queue, null), role), "{}"));

        assertFailed(completed, "SQS.AccessDeniedException");
        assertEquals(0, queue.receive().size());
    }

    @Test
    void credentialsWithoutARoleArnDoNotFallBackToTheExecutionRole() throws Exception {
        SqsTestQueue queue = queue();
        String machine = machine(Map.of("Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage",
                "Credentials", Map.of("Role", "x"),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true),
                role(STATES_TRUST, SEND));

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, "States.Runtime");
        assertEquals("Task Credentials must resolve to an object containing RoleArn",
                completed.jsonPath().getString("cause"));
        assertEquals(0, queue.receive().size());
    }

    @Test
    void waitForTaskTokenRefusedDoesNotWaitAndLeavesNoMessage() throws Exception {
        SqsTestQueue queue = queue();
        String machine = machine(Map.of("Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage.waitForTaskToken",
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", Map.of("token.$", "$$.Task.Token")),
                "TimeoutSeconds", 60, "End", true), role(STATES_TRUST, READ_ONLY));

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, "SQS.AccessDeniedException");
        assertEquals(0, queue.receive().size());
    }

    @Test
    void waitForTaskTokenAllowedSendsAndWaits() throws Exception {
        SqsTestQueue queue = queue();
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), SEND);
        String machine = machine(Map.of("Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage.waitForTaskToken",
                "Credentials", Map.of("RoleArn", taskRole),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", Map.of("token.$", "$$.Task.Token")),
                "TimeoutSeconds", 60, "End", true), executionRole);

        String execution = start(machine, "{}");
        String token = queue.awaitJson(Duration.ofSeconds(5)).path("token").asText();
        request("SendTaskSuccess", Map.of("taskToken", token, "output", "{\"done\":true}")).then().statusCode(200);
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @ParameterizedTest
    @CsvSource({"sqs:sendMessage,SQS.AccessDeniedException", "aws-sdk:sqs:sendMessage,Sqs.AccessDeniedException"})
    void aRefusalCanBeRetriedAndCaught(String integration, String error) throws Exception {
        SqsTestQueue queue = queue();
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask(integration, queue, null));
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of(error), "IntervalSeconds", 1,
                "BackoffRate", 1.0, "MaxAttempts", 1)));
        task.put("Catch", List.of(Map.of("ErrorEquals", List.of(error), "Next", "Recovered")));
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", task,
                "Recovered", Map.of("Type", "Pass", "End", true))));
        String execution = start(machineWithDefinition(definition, role(STATES_TRUST, READ_ONLY)), "{}");

        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(2, eventsOfType(execution, "TaskFailed").size());
        assertEquals(error, eventsOfType(execution, "TaskFailed").getLast()
                .path("taskFailedEventDetails").path("error").asText());
    }

    /** The retry decides again, so a role granted the action between attempts then sends. */
    @Test
    void aRetryDecidesAgain() throws Exception {
        SqsTestQueue queue = queue();
        String roleName = "sfn-sqs-" + UUID.randomUUID();
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", roleName,
                STATES_TRUST, READ_ONLY);
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask("sqs:sendMessage", queue, null));
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of("SQS.AccessDeniedException"), "IntervalSeconds", 2,
                "BackoffRate", 1.0, "MaxAttempts", 3)));
        String execution = start(machine(task, role), "{}");

        await(execution, "TaskFailed");
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "PutRolePolicy").formParam("Version", "2010-05-08")
                .formParam("RoleName", roleName).formParam("PolicyName", "p").formParam("PolicyDocument", SEND)
                .post("/").then().statusCode(200);
        Response completed = terminal(execution, 20);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    private static String allow(String... actions) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                    Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", "*"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trusting(String role) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                 "Principal":{"AWS":"%s"}}]}
                """.formatted(role);
    }

    private static String queuePolicy(String effect, String principal, String queueArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"sqs:SendMessage","Resource":"%s"}]}
                """.formatted(effect, principal, queueArn);
    }

    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    private Map<String, Object> jsonPathTask(String integration, SqsTestQueue queue, String taskRole) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integration,
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true));
        if (taskRole != null) {
            task.put("Credentials", Map.of("RoleArn", taskRole));
        }
        return task;
    }

    private Map<String, Object> task(String integration, SqsTestQueue queue, String form, String taskRole) {
        return switch (form) {
            case "jsonpath" -> jsonPathTask(integration, queue, taskRole);
            case "jsonpath-path" -> {
                Map<String, Object> task = jsonPathTask(integration, queue, null);
                task.put("Credentials", Map.of("RoleArn.$", "$.role"));
                yield task;
            }
            default -> Map.of("Type", "Task", "QueryLanguage", "JSONata",
                    "Resource", "arn:aws:states:::" + integration,
                    "Credentials", Map.of("RoleArn", "{% $states.input.role %}"),
                    "Arguments", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true);
        };
    }

    private SqsTestQueue queue() {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "sfn-sqs-iam-");
        cleanup.register(queue::delete);
        return queue;
    }

    private String role(String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/",
                "sfn-sqs-" + UUID.randomUUID(), trust, policy);
    }

    private String machine(Map<String, Object> task, String role) throws Exception {
        return machineWithDefinition(MAPPER.writeValueAsString(
                Map.of("StartAt", "Send", "States", Map.of("Send", task))), role);
    }

    private String machineWithDefinition(String definition, String role) {
        return sfn().createMachine(ACCOUNT, "sfn-sqs-iam-", definition, "STANDARD", role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return terminal(execution, 5);
    }

    private Response terminal(String execution, int seconds) throws Exception {
        return sfn().terminal(ACCOUNT, execution, seconds);
    }

    private void await(String execution, String eventType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (!eventsOfType(execution, eventType).isEmpty()) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("No " + eventType + " event in " + execution);
    }

    private List<JsonNode> eventsOfType(String execution, String type) throws Exception {
        return sfn().eventsOfType(ACCOUNT, execution, type);
    }

    private Response request(String action, Map<String, ?> input) {
        return sfn().request(ACCOUNT, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }
}
