package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.github.hectorvent.floci.testing.StepFunctionsTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class StepFunctionsTaskCredentialsIntegrationTest {
    private static final String SOURCE = "000000000331";
    private static final String TARGET = "000000000332";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASS = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """;

    @TestHTTPResource
    URI baseUri;

    @InjectMock
    LambdaExecutorService lambdaExecutor;

    @InjectSpy
    JsonataEvaluator jsonataEvaluator;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync",
            "states:startExecution.sync:2", "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void nestedTaskUsesRoleAccountAndRestoresParentAccount(String resource) throws Exception {
        String child = machine(TARGET, PASS, resource.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD");
        String ownChild = machine(SOURCE, PASS, "STANDARD");
        ObjectNode definition = definition(child, resource, role());
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.remove("End");
        task.put("Next", "OwnChild");
        task.put("ResultPath", "$.child");
        ((ObjectNode) definition.path("States")).set("OwnChild", MAPPER.readTree("""
                {"Type":"Task","Resource":"arn:aws:states:::states:startExecution.sync:2",
                 "Parameters":{"StateMachineArn":"%s","Input":{"restored":true}},"End":true}
                """.formatted(ownChild)));
        String parent = machine(SOURCE, definition.toString(), "STANDARD");
        String execution = start(parent, "{}");
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        JsonNode output = MAPPER.readTree(done.jsonPath().getString("output"));
        assertEquals(true, output.path("Output").path("restored").asBoolean());
        request(TARGET, "DescribeExecution", Map.of("executionArn", execution)).then().statusCode(400);
        Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
        history.then().statusCode(200);
        assertTrue(history.asString().contains(child), history.asString());
        assertTrue(history.asString().contains("ExecutionSucceeded"), history.asString());
    }

    @Test
    void dynamicRoleUsesInputPathBeforeParameters() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        String taskRole = role();
        ObjectNode definition = definition(child, "states:startExecution.sync:2", taskRole);
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.put("InputPath", "$.selected");
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn.$", "$.role")));
        String input = MAPPER.writeValueAsString(Map.of("selected", Map.of("role", taskRole)));
        String execution = start(machine(SOURCE, definition.toString(), "STANDARD"), input);
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
    }

    @Test
    void jsonataRoleUsesOriginalStateInput() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        definition.put("QueryLanguage", "JSONata");
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.set("Arguments", task.remove("Parameters"));
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn", "{% $states.input.role %}")));
        String parent = machine(SOURCE, definition.toString(), "STANDARD");
        clearInvocations(jsonataEvaluator);
        Response done = terminal(SOURCE, start(parent,
                MAPPER.writeValueAsString(Map.of("role", role()))));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        verify(jsonataEvaluator, times(1)).resolveTemplate(any(), eq("Credentials"), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2",
            "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void foreignArnAloneDoesNotGrantAccountAccess(String resource) throws Exception {
        String child = machine(TARGET, PASS, resource.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD");
        ObjectNode definition = definition(child, resource, role());
        ((ObjectNode) definition.path("States").path("Child")).remove("Credentials");
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals(resource.startsWith("aws-sdk:") ? "Sfn.SfnException" : "StepFunctions.AWSStepFunctionsException",
                done.jsonPath().getString("error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-path", "foreign-partition", "invalid-arn"})
    void invalidCredentialsCannotStartChild(String invalid) throws Exception {
        String existing = role();
        String value = switch (invalid) {
            case "missing" -> existing + "-missing";
            case "wrong-path" -> existing.replace(":role/", ":role/different/");
            case "foreign-partition" -> existing.replace("arn:aws:", "arn:aws-cn:");
            default -> "not-an-arn";
        };
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", value);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        if ("missing".equals(invalid) || "wrong-path".equals(invalid)) {
            assertEquals("States.TaskFailed", done.jsonPath().getString("error"));
        } else {
            assertEquals("States.Runtime", done.jsonPath().getString("error"));
        }
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", child)).then()
                .statusCode(200).body("executions.size()", Matchers.equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-role", "extra-member", "non-object", "null-role", "numeric-role"})
    void malformedCredentialsCannotStartChild(String invalid) throws Exception {
        String taskRole = role();
        JsonNode credentials = switch (invalid) {
            case "missing-role" -> MAPPER.createObjectNode();
            case "extra-member" -> MAPPER.valueToTree(Map.of("RoleArn", taskRole, "Extra", true));
            case "non-object" -> MAPPER.createArrayNode().add(taskRole);
            case "null-role" -> MAPPER.readTree("{\"RoleArn\":null}");
            default -> MAPPER.valueToTree(Map.of("RoleArn", 42));
        };
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", taskRole);
        ((ObjectNode) definition.path("States").path("Child")).set("Credentials", credentials);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("States.Runtime", done.jsonPath().getString("error"));
        assertEquals("Task Credentials must resolve to an object containing RoleArn",
                done.jsonPath().getString("cause"));
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", child)).then()
                .statusCode(200).body("executions.size()", Matchers.equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "optimized"})
    void lambdaCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        String name = "credentials-worker-" + UUID.randomUUID();
        String function = "arn:aws:lambda:us-east-1:" + SOURCE + ":function:" + name;
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(SOURCE, "lambda"))
                .delete("/2015-03-31/functions/{name}", name).then().statusCode(204));
        given().port(baseUri.getPort()).header("Authorization", auth(SOURCE, "lambda"))
                .contentType("application/json").body(Map.of(
                        "FunctionName", name, "Runtime", "nodejs20.x", "Handler", "index.handler",
                        "Role", "arn:aws:iam::" + SOURCE + ":role/lambda-role"))
                .post("/2015-03-31/functions").then().statusCode(201);
        when(lambdaExecutor.invoke(any(), any(), any())).thenReturn(
                new InvokeResult(200, null, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req"));
        ObjectNode definition = definition(function, "lambda:invoke", role());
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        if ("direct".equals(integration)) {
            task.put("Resource", function);
            task.set("Parameters", MAPPER.createObjectNode());
        } else {
            task.set("Parameters", MAPPER.valueToTree(Map.of("FunctionName", function)));
        }
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(done.jsonPath().getString("output").contains("\"ok\":true"), done.asString());
        verify(lambdaExecutor).invoke(argThat(fn -> fn != null && function.equals(fn.getFunctionArn())), any(), any());
    }

    /** The execution role does not exist, and neither does the Credentials role: nothing evaluates either. */
    @ParameterizedTest
    @ValueSource(strings = {"sqs:sendMessage", "aws-sdk:sqs:sendMessage"})
    void sqsCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, SOURCE, "us-east-1", "credentials-");
        cleanup.register(queue::delete);
        String taskRole = "arn:aws:iam::" + TARGET + ":role/no-such-role";
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Credentials", Map.of("RoleArn", taskRole),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
        assertEquals(taskRole, taskScheduled(execution).path("taskCredentials").path("roleArn").asText());
    }

    @Test
    void credentialsThatDoNotResolveStillPassWithEnforcementOff() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, SOURCE, "us-east-1", "credentials-");
        cleanup.register(queue::delete);
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage",
                "Credentials", Map.of("RoleArn.$", "$.missing"),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(taskScheduled(execution).path("taskCredentials").isMissingNode(), done.asString());
    }

    private JsonNode taskScheduled(String execution) throws Exception {
        return sfn().eventsOfType(SOURCE, execution, "TaskScheduled").getFirst().path("taskScheduledEventDetails");
    }

    @Test
    void childFailureIsCaughtInParentAccount() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Fail","States":{"Fail":{"Type":"Fail","Error":"ChildFailure"}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).set("Catch",
                MAPPER.readTree("[{\"ErrorEquals\":[\"States.TaskFailed\"],\"Next\":\"Recovered\"}]"));
        ((ObjectNode) definition.path("States")).set("Recovered",
                MAPPER.readTree("{\"Type\":\"Pass\",\"End\":true}"));
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(done.jsonPath().getString("output").contains("ChildFailure"), done.asString());
    }

    @Test
    void stoppingParentStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        String parent = machine(SOURCE, definition(child, "states:startExecution.sync:2", role()).toString(),
                "STANDARD");
        String execution = start(parent, "{}");
        String childExecution = null;
        for (int i = 0; i < 100; i++) {
            Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
            history.then().statusCode(200);
            for (JsonNode event : MAPPER.readTree(history.asString()).path("events")) {
                if ("TaskSubmitted".equals(event.path("type").asText())) {
                    childExecution = MAPPER.readTree(event.path("taskSubmittedEventDetails").path("output").asText())
                            .path("ExecutionArn").asText();
                }
            }
            if (childExecution != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(childExecution != null && childExecution.contains(TARGET));
        request(SOURCE, "StopExecution", Map.of("executionArn", execution)).then().statusCode(200);
        assertEquals("ABORTED", terminal(TARGET, childExecution).jsonPath().getString("status"));
        assertEquals("ABORTED", terminal(SOURCE, execution).jsonPath().getString("status"));
    }

    @Test
    void taskTimeoutStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).put("TimeoutSeconds", 1);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("States.Timeout", done.jsonPath().getString("error"));
        Response children = request(TARGET, "ListExecutions", Map.of("stateMachineArn", child));
        children.then().statusCode(200).body("executions.size()", Matchers.equalTo(1));
        String execution = children.jsonPath().getString("executions[0].executionArn");
        assertEquals("ABORTED", terminal(TARGET, execution).jsonPath().getString("status"));
    }

    private static ObjectNode definition(String child, String resource, String role) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"StartAt":"Child","States":{"Child":{"Type":"Task","Resource":"arn:aws:states:::%s",
                "Credentials":{"RoleArn":"%s"},"Parameters":{"StateMachineArn":"%s","Input":{}},"End":true}}}
                """.formatted(resource, role, child));
    }

    private String role() {
        String name = "nested-" + UUID.randomUUID();
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), TARGET, "/", name, """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                        "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}
                        """.formatted(SOURCE), """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"states:StartExecution","Resource":"*"}]}
                        """);
    }

    private String machine(String account, String definition, String type) throws Exception {
        return sfn().createMachine(account, "credentials-", definition, type,
                "arn:aws:iam::" + account + ":role/execution");
    }

    private String start(String machine, String input) throws Exception {
        return sfn().start(SOURCE, machine, input);
    }

    private Response terminal(String account, String execution) throws Exception {
        return sfn().terminal(account, execution, 5);
    }

    private Response request(String account, String action, Map<String, ?> input) {
        return sfn().request(account, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }
}
