package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Task result of {@code states:startExecution} in its three modes, the way AWS returns it
 * (measured in ap-northeast-1 on 2026-09-30): the StartExecution response for request-response and
 * the child's DescribeExecution response for {@code .sync} and {@code .sync:2}, all PascalCase with
 * dates in epoch milliseconds, {@code .sync:2} carrying {@code Input} and {@code Output} as JSON
 * values where {@code .sync} carries JSON strings.
 */
@QuarkusTest
class StepFunctionsNestedStartExecutionResultIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final List<String> ENVELOPE_KEYS = List.of("ExecutionArn", "Input", "InputDetails", "Name",
            "Output", "OutputDetails", "RedriveCount", "RedriveStatus", "RedriveStatusReason", "StartDate",
            "StateMachineArn", "Status", "StopDate");
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void eachModeReturnsItsPascalCaseResult() throws Exception {
        String childArn = createStateMachine("nested-result-child", """
                {"StartAt": "D", "States": {"D": {"Type": "Pass", "Result": {"ok": true}, "End": true}}}
                """);
        String parentArn = createStateMachine("nested-result-parent", """
                {
                    "StartAt": "RR",
                    "States": {
                        "RR": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution",
                            "Parameters": {"StateMachineArn": "%1$s", "Input": {"k": 1}},
                            "ResultPath": "$.rr",
                            "Next": "Sync"
                        },
                        "Sync": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync",
                            "Parameters": {"StateMachineArn": "%1$s", "Input": {"k": 1}},
                            "ResultPath": "$.sync",
                            "Next": "Sync2"
                        },
                        "Sync2": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync:2",
                            "Parameters": {"StateMachineArn": "%1$s", "Input": {"k": 1}},
                            "ResultPath": "$.sync2",
                            "Next": "Unwrap"
                        },
                        "Unwrap": {
                            "Type": "Pass",
                            "Parameters": {"ok.$": "$.sync2.Output.ok"},
                            "ResultPath": "$.unwrapped",
                            "End": true
                        }
                    }
                }
                """.formatted(childArn));

        Response done = waitForTerminalExecution(startExecution(parentArn, "{}"));

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.body().asString());
        JsonNode output = mapper.readTree(done.jsonPath().getString("output"));

        JsonNode requestResponse = output.path("rr");
        assertEquals(List.of("ExecutionArn", "StartDate"), fieldNames(requestResponse));
        assertTrue(requestResponse.path("ExecutionArn").asText().contains(":execution:nested-result-child"),
                requestResponse.toString());
        assertTrue(requestResponse.path("StartDate").isIntegralNumber(), "epoch milliseconds: " + requestResponse);

        JsonNode sync = output.path("sync");
        assertEquals(ENVELOPE_KEYS, fieldNames(sync));
        assertEquals("{\"k\":1}", sync.path("Input").asText());
        assertEquals("{\"ok\":true}", sync.path("Output").asText());
        assertEquals(childArn, sync.path("StateMachineArn").asText());
        assertEquals("SUCCEEDED", sync.path("Status").asText());
        assertEquals("NOT_REDRIVABLE", sync.path("RedriveStatus").asText());
        assertEquals("Execution is SUCCEEDED and cannot be redriven", sync.path("RedriveStatusReason").asText());
        assertTrue(sync.path("InputDetails").path("Included").asBoolean(), sync.toString());
        assertTrue(sync.path("OutputDetails").path("Included").asBoolean(), sync.toString());
        assertTrue(sync.path("StartDate").isIntegralNumber(), "epoch milliseconds: " + sync);
        assertTrue(sync.path("StopDate").isIntegralNumber(), "epoch milliseconds: " + sync);

        JsonNode sync2 = output.path("sync2");
        assertEquals(ENVELOPE_KEYS, fieldNames(sync2));
        assertEquals(mapper.readTree("{\"k\":1}"), sync2.path("Input"));
        assertEquals(mapper.readTree("{\"ok\":true}"), sync2.path("Output"));
        assertEquals("SUCCEEDED", sync2.path("Status").asText());

        assertTrue(output.path("unwrapped").path("ok").asBoolean(), "$.Output of .sync:2 is addressable: " + output);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private static String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s-%d", "definition": %s, "roleArn": "%s"}
                        """.formatted(name, System.currentTimeMillis(), quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private static String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": %s}
                        """.formatted(smArn, quote(input)))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private static Response describeExecution(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
    }

    private static Response waitForTerminalExecution(String execArn) throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            Response resp = describeExecution(execArn);
            if (!"RUNNING".equals(resp.jsonPath().getString("status"))) {
                return resp;
            }
            Thread.sleep(100);
        }
        fail("Execution " + execArn + " was still RUNNING after 20 seconds");
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
