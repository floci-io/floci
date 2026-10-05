package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.durable.DurableFunctionInvoker.DurableInvocationResult;
import io.github.hectorvent.floci.services.lambda.durable.ScriptedDurableFunctionInvoker.Invocation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays the Durable Execution SDK over the REST API while a scripted stand-in holds the function
 * invocation open, so the checkpoint protocol is exercised end to end without a container.
 */
@QuarkusTest
@TestProfile(LambdaDurableScriptedProfile.class)
class LambdaDurableProtocolIntegrationTest {

    private static final String LAMBDA = "/2015-03-31";
    private static final String DURABLE = "/2025-12-01";
    private static final String FUNCTION = "protocol-durable-fn";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ScriptedDurableFunctionInvoker invoker;

    @BeforeEach
    void createFunctionAndDrainInvocations() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "DurableConfig": {"ExecutionTimeout": 300, "RetentionPeriodInDays": 1}
                }
                """.formatted(FUNCTION))
        .when()
            .post(LAMBDA + "/functions")
        .then()
            .statusCode(anyOf(is(201), is(409)));
        invoker.drain();
    }

    @Test
    void aSynchronousInvokeReturnsTheResultOfACheckpointedExecution() throws Exception {
        CompletableFuture<Response> invoke = CompletableFuture.supplyAsync(() -> given()
                .header("X-Amz-Durable-Execution-Name", "sync-1")
                .body("{\"order\": 1}")
                .post(LAMBDA + "/functions/" + FUNCTION + ":$LATEST/invocations"));

        Invocation invocation = invoker.awaitInvocation(Duration.ofSeconds(30));
        JsonNode event = MAPPER.readTree(invocation.payload());
        String arn = event.get("DurableExecutionArn").asText();
        String token = event.get("CheckpointToken").asText();
        assertEquals("{\"order\": 1}", event.at("/InitialExecutionState/Operations/0/ExecutionDetails/InputPayload")
                .asText());

        Response checkpoint = given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body("""
                {
                    "CheckpointToken": "%s",
                    "Updates": [
                        {"Id": "s1", "Name": "validate", "Type": "STEP", "SubType": "Step", "Action": "START"},
                        {"Id": "s1", "Name": "validate", "Type": "STEP", "SubType": "Step", "Action": "SUCCEED",
                         "Payload": "{\\"ok\\":true}"}
                    ]
                }
                """.formatted(token))
            .post(DURABLE + "/durable-executions/" + arn + "/checkpoint");
        checkpoint.then()
            .statusCode(200)
            .body("CheckpointToken", notNullValue())
            .body("NewExecutionState.Operations", hasSize(1))
            .body("NewExecutionState.Operations[0].Id", equalTo("s1"))
            .body("NewExecutionState.Operations[0].Status", equalTo("SUCCEEDED"))
            .body("NewExecutionState.Operations[0].StepDetails.Result", equalTo("{\"ok\":true}"))
            .body("NewExecutionState.Operations[0].StepDetails.Attempt", equalTo(1));
        String nextToken = checkpoint.jsonPath().getString("CheckpointToken");

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "/state?CheckpointToken=" + nextToken)
        .then()
            .statusCode(200)
            .body("Operations", hasSize(2))
            .body("Operations[0].Type", equalTo("EXECUTION"))
            .body("Operations[1].Id", equalTo("s1"))
            .body("NextMarker", equalTo(""));

        given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body("{\"CheckpointToken\": \"" + token + "\", \"Updates\": []}")
        .when()
            .post(DURABLE + "/durable-executions/" + arn + "/checkpoint")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("Invalid checkpoint token"));

        invocation.response().complete(handlerResponse("{\"Status\": \"SUCCEEDED\", \"Result\": \"{\\\"total\\\":3}\"}"));

        Response response = invoke.get(30, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals(arn, response.getHeader("X-Amz-Durable-Execution-Arn"));
        assertEquals("$LATEST", response.getHeader("X-Amz-Executed-Version"));
        assertEquals("{\"total\":3}", response.asString());

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn)
        .then()
            .statusCode(200)
            .body("Status", equalTo("SUCCEEDED"))
            .body("DurableExecutionName", equalTo("sync-1"))
            .body("Version", equalTo("$LATEST"))
            .body("InputPayload", equalTo("{\"order\": 1}"))
            .body("Result", equalTo("{\"total\":3}"))
            .body("ExecutionDataIncluded", equalTo(true))
            .body("DurableConfig.ExecutionTimeout", equalTo(300))
            .body("EndTimestamp", notNullValue());

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "?IncludeExecutionData=false")
        .then()
            .statusCode(200)
            .body("ExecutionDataIncluded", equalTo(false))
            .body("$", not(hasKey("InputPayload")))
            .body("$", not(hasKey("Result")));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "/history")
        .then()
            .statusCode(200)
            .body("Events.EventType", equalTo(List.of("ExecutionStarted", "StepStarted", "StepSucceeded",
                    "InvocationCompleted", "ExecutionSucceeded")))
            .body("Events[0].Id", notNullValue())
            .body("Events[0].Name", equalTo("sync-1"))
            .body("Events[0].ExecutionStartedDetails.Input.Truncated", equalTo(true))
            .body("Events[0].ExecutionStartedDetails.Input", not(hasKey("Payload")))
            .body("Events[2].Id", equalTo("s1"))
            .body("Events[2].Name", equalTo("validate"))
            .body("Events[2].SubType", equalTo("Step"))
            .body("Events[3].InvocationCompletedDetails.RequestId", notNullValue());

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "/history?IncludeExecutionData=true&ReverseOrder=true&MaxItems=2")
        .then()
            .statusCode(200)
            .body("Events.EventType", equalTo(List.of("ExecutionSucceeded", "InvocationCompleted")))
            .body("Events[0].ExecutionSucceededDetails.Result.Payload", equalTo("{\"total\":3}"))
            .body("Events[0].ExecutionSucceededDetails.Result.Truncated", equalTo(false))
            .body("NextMarker", notNullValue());

        String encoded = arn.replace(":", "%3A").replace("/", "%2F").replace("$", "%24");
        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + encoded)
        .then()
            .statusCode(200)
            .body("DurableExecutionArn", equalTo(arn));
    }

    @Test
    void anAsynchronousInvokeIsIdempotentByNameAndCanBeStopped() throws Exception {
        Response accepted = given()
                .header("X-Amz-Invocation-Type", "Event")
                .header("X-Amz-Durable-Execution-Name", "async-1")
                .body("{\"job\": 1}")
                .post(LAMBDA + "/functions/" + FUNCTION + ":$LATEST/invocations");
        assertEquals(202, accepted.statusCode());
        String arn = accepted.getHeader("X-Amz-Durable-Execution-Arn");
        assertTrue(arn.contains("/durable-execution/async-1/"), arn);

        Invocation invocation = invoker.awaitInvocation(Duration.ofSeconds(30));
        JsonNode event = MAPPER.readTree(invocation.payload());
        assertEquals(arn, event.get("DurableExecutionArn").asText());
        given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body("""
                {
                    "CheckpointToken": "%s",
                    "Updates": [{"Id": "w1", "Type": "WAIT", "SubType": "Wait", "Action": "START",
                                 "WaitOptions": {"WaitSeconds": 600}}]
                }
                """.formatted(event.get("CheckpointToken").asText()))
        .when()
            .post(DURABLE + "/durable-executions/" + arn + "/checkpoint")
        .then()
            .statusCode(200)
            .body("NewExecutionState.Operations[0].Status", equalTo("STARTED"))
            .body("NewExecutionState.Operations[0].WaitDetails.ScheduledEndTimestamp", notNullValue());
        invocation.response().complete(handlerResponse("{\"Status\": \"PENDING\"}"));

        given()
            .header("X-Amz-Invocation-Type", "Event")
            .header("X-Amz-Durable-Execution-Name", "async-1")
            .body("{\"job\": 1}")
        .when()
            .post(LAMBDA + "/functions/" + FUNCTION + ":$LATEST/invocations")
        .then()
            .statusCode(202)
            .header("X-Amz-Durable-Execution-Arn", equalTo(arn));

        given()
            .header("X-Amz-Invocation-Type", "Event")
            .header("X-Amz-Durable-Execution-Name", "async-1")
            .body("{\"job\": 2}")
        .when()
            .post(LAMBDA + "/functions/" + FUNCTION + ":$LATEST/invocations")
        .then()
            .statusCode(409)
            .body("__type", equalTo("DurableExecutionAlreadyStartedException"))
            .body("message", equalTo("Execution already started: " + arn));

        given()
        .when()
            .get(DURABLE + "/functions/" + FUNCTION + "/durable-executions?Statuses=RUNNING&DurableExecutionName=async-1")
        .then()
            .statusCode(200)
            .body("DurableExecutions", hasSize(1))
            .body("DurableExecutions[0].DurableExecutionArn", equalTo(arn))
            .body("DurableExecutions[0].Status", equalTo("RUNNING"))
            .body("DurableExecutions[0]", not(hasKey("EndTimestamp")));

        given()
            .urlEncodingEnabled(false)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-west-2/lambda/"
                    + "aws4_request, SignedHeaders=host, Signature=abc")
            .contentType("application/json")
            .body("{}")
        .when()
            .post(DURABLE + "/durable-executions/" + arn + "/stop")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Function not found"));

        given()
            .urlEncodingEnabled(false)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=111111111111/20260101/us-east-1/lambda/"
                    + "aws4_request, SignedHeaders=host, Signature=abc")
            .contentType("application/json")
            .body("{}")
        .when()
            .post(DURABLE + "/durable-executions/" + arn + "/stop")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body("{\"ErrorType\": \"Cancelled\", \"ErrorMessage\": \"by test\"}")
        .when()
            .post(DURABLE + "/durable-executions/" + arn + "/stop")
        .then()
            .statusCode(200)
            .body("StopTimestamp", notNullValue());

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn)
        .then()
            .statusCode(200)
            .body("Status", equalTo("STOPPED"))
            .body("Error.ErrorType", equalTo("Cancelled"))
            .body("Error.ErrorMessage", equalTo("by test"));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "/history?ReverseOrder=true&MaxItems=1")
        .then()
            .statusCode(200)
            .body("Events[0].EventType", equalTo("ExecutionStopped"));
    }

    private static DurableInvocationResult handlerResponse(String json) {
        return new DurableInvocationResult("req-" + System.nanoTime(), json.getBytes(StandardCharsets.UTF_8), null);
    }
}
