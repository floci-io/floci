package io.floci.aws.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.sqs.SqsService;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

/** Step Functions calls a test makes as one account, and the state machines it leaves to {@code cleanup}. */
public final class StepFunctionsTestClient {

    /** A trust policy letting Step Functions assume the role: what an execution role needs. */
    public static final String STATES_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"states.amazonaws.com"}}]}
            """;
    /** A trust policy naming another service, so Step Functions may not assume the role. */
    public static final String LAMBDA_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
             "Principal":{"Service":"lambda.amazonaws.com"}}]}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_MILLIS = 25;

    private final int port;
    private final PartitionCleanup cleanup;

    public StepFunctionsTestClient(int port, PartitionCleanup cleanup) {
        this.port = port;
        this.cleanup = cleanup;
    }

    public static String allow(String resource, String... actions) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                    Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", resource))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String role(String account, String namePrefix, String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, port, account, "/", namePrefix + UUID.randomUUID(), trust, policy);
    }

    public SqsTestQueue queue(SqsService service, String account, String region, String namePrefix) {
        SqsTestQueue queue = SqsTestQueue.create(service, account, region, namePrefix);
        cleanup.register(queue::delete);
        return queue;
    }

    public String machine(String account, String namePrefix, Map<String, Object> task, String role) throws Exception {
        return machineWithDefinition(account, namePrefix, MAPPER.writeValueAsString(
                Map.of("StartAt", "Send", "States", Map.of("Send", task))), role);
    }

    public String machineWithDefinition(String account, String namePrefix, String definition, String role) {
        return createMachine(account, namePrefix, definition, "STANDARD", role);
    }

    public Response request(String account, String action, Map<String, ?> input) {
        return json(account, "states", "AWSStepFunctions." + action, "application/x-amz-json-1.0", input);
    }

    /**
     * A JSON-protocol request to the service a task calls, signed as {@code account} for {@code signingName}, so a
     * test reads and sets up the resources a task reaches in the account its execution runs in.
     */
    public Response json(String account, String signingName, String target, String contentType, Map<String, ?> body) {
        return given().port(port).header("Authorization", auth(account, signingName))
                .header("X-Amz-Target", target)
                .contentType(contentType).body(MAPPER.valueToTree(body).toString()).post("/");
    }

    /** Creates a state machine in us-east-1, which the end of the test stops and deletes. */
    public String createMachine(String account, String namePrefix, String definition, String type, String role) {
        return createMachineNamed(account, namePrefix + UUID.randomUUID(), definition, type, role);
    }

    /** {@link #createMachine} under the exact {@code name}, for a test that needs the machine's ARN up front. */
    public String createMachineNamed(String account, String name, String definition, String type, String role) {
        String arn = "arn:aws:states:us-east-1:" + account + ":stateMachine:" + name;
        cleanup.register(() -> removeMachine(account, arn));
        Response created = request(account, "CreateStateMachine", Map.of(
                "name", name, "definition", definition, "type", type, "roleArn", role));
        created.then().statusCode(200);
        return created.jsonPath().getString("stateMachineArn");
    }

    public String start(String account, String machine, String input) {
        Response started = request(account, "StartExecution", Map.of("stateMachineArn", machine, "input", input));
        started.then().statusCode(200);
        return started.jsonPath().getString("executionArn");
    }

    /** Polls until the execution ends: nothing in the API waits for a standard workflow. */
    public Response terminal(String account, String execution, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            Response response = request(account, "DescribeExecution", Map.of("executionArn", execution));
            response.then().statusCode(200);
            if (!"RUNNING".equals(response.jsonPath().getString("status"))) {
                return response;
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        fail("Execution did not terminate: " + execution);
        return null;
    }

    /** Polls until the execution's history holds an event of {@code eventType}. */
    public void await(String account, String execution, String eventType, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            if (!eventsOfType(account, execution, eventType).isEmpty()) {
                return;
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        fail("No " + eventType + " event in " + execution);
    }

    public List<JsonNode> eventsOfType(String account, String execution, String type) throws Exception {
        Response history = request(account, "GetExecutionHistory", Map.of("executionArn", execution));
        history.then().statusCode(200);
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode event : MAPPER.readTree(history.asString()).path("events")) {
            if (type.equals(event.path("type").asText())) {
                events.add(event);
            }
        }
        return events;
    }

    /** A trust policy letting {@code roleArn} assume the role, as a task's {@code Credentials} role needs. */
    public static String trusting(String roleArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                 "Principal":{"AWS":"%s"}}]}
                """.formatted(roleArn);
    }

    private void removeMachine(String account, String arn) {
        Response executions = request(account, "ListExecutions", Map.of("stateMachineArn", arn));
        if (executions.statusCode() == 200) {
            for (Map<String, String> execution : executions.jsonPath().<Map<String, String>>getList("executions")) {
                if ("RUNNING".equals(execution.get("status"))) {
                    request(account, "StopExecution", Map.of("executionArn", execution.get("executionArn")))
                            .then().statusCode(200);
                }
            }
        }
        request(account, "DeleteStateMachine", Map.of("stateMachineArn", arn)).then().statusCode(200);
    }
}
