package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A {@code Parallel} fails the moment any of its branches fails, whichever branch is listed first,
 * the way AWS does (measured in ap-northeast-1 on 2026-09-29: the execution fails within about
 * 0.1 s of the failing branch while a sibling listed before it is still running). The branch that
 * was still in its {@code Wait} records {@code WaitStateAborted} and never exits.
 */
@QuarkusTest
class StepFunctionsParallelBranchFailureIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFailureInTheSecondBranchEndsTheParallelWhileTheFirstStillWaits() throws Exception {
        String smArn = createStateMachine("parallel-later-branch-fails", """
                {
                    "StartAt": "P",
                    "States": {
                        "P": {
                            "Type": "Parallel",
                            "End": true,
                            "Branches": [
                                {"StartAt": "Long", "States": {"Long": {"Type": "Wait", "Seconds": 15, "End": true}}},
                                {"StartAt": "Pause", "States": {
                                    "Pause": {"Type": "Wait", "Seconds": 1, "Next": "Boom"},
                                    "Boom": {"Type": "Fail", "Error": "Boom", "Cause": "sibling failed"}}}
                            ]
                        }
                    }
                }
                """);
        String execArn = startExecution(smArn, "{}");

        // Well inside the first branch's 15 s Wait: a declaration-order join is still RUNNING here.
        Response failed = waitForTerminalExecution(execArn, 8_000);

        assertEquals("FAILED", failed.jsonPath().getString("status"), failed.body().asString());
        assertEquals("Boom", failed.jsonPath().getString("error"));
        assertEquals("sibling failed", failed.jsonPath().getString("cause"));
        Response history = getExecutionHistory(execArn);
        List<String> types = history.jsonPath().getList("events.type");
        assertTrue(types.contains("WaitStateAborted"), types.toString());
        assertTrue(types.indexOf("WaitStateAborted") < types.indexOf("ParallelStateFailed"), types.toString());
        // The failing branch's own Pause exits; the cut Long never does.
        List<String> exitedWaits = history.jsonPath()
                .getList("events.findAll { it.type == 'WaitStateExited' }.stateExitedEventDetails.name");
        assertEquals(List.of("Pause"), exitedWaits, types.toString());
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

    private static Response getExecutionHistory(String execArn) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp;
    }

    private static Response waitForTerminalExecution(String execArn, long limitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + limitMillis;
        while (System.currentTimeMillis() < deadline) {
            Response resp = describeExecution(execArn);
            if (!"RUNNING".equals(resp.jsonPath().getString("status"))) {
                return resp;
            }
            Thread.sleep(100);
        }
        fail("Execution " + execArn + " was still RUNNING after " + limitMillis + " ms");
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
