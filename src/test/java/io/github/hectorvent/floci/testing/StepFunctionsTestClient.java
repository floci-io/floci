package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

/** Step Functions calls a test makes as one account, and the state machines it leaves to {@code cleanup}. */
public final class StepFunctionsTestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_MILLIS = 25;

    private final int port;
    private final PartitionCleanup cleanup;

    public StepFunctionsTestClient(int port, PartitionCleanup cleanup) {
        this.port = port;
        this.cleanup = cleanup;
    }

    public Response request(String account, String action, Map<String, ?> input) {
        return given().port(port).header("Authorization", auth(account, "states"))
                .header("X-Amz-Target", "AWSStepFunctions." + action)
                .contentType("application/x-amz-json-1.0").body(MAPPER.valueToTree(input).toString()).post("/");
    }

    /** Creates a state machine in us-east-1, which the end of the test stops and deletes. */
    public String createMachine(String account, String namePrefix, String definition, String type, String role) {
        String name = namePrefix + UUID.randomUUID();
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
