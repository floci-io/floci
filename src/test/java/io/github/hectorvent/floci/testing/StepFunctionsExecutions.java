package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** Step Functions execution calls shared by the integration tests. */
public final class StepFunctionsExecutions {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final Duration TERMINAL_STATE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private StepFunctionsExecutions() {}

    public static Response describeExecution(String executionArn) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(executionArn))
                .when().post("/");
        response.then().statusCode(200);
        return response;
    }

    /**
     * Polls DescribeExecution until the execution leaves RUNNING and returns that response, whatever
     * the terminal status: asserting which one is the caller's business.
     */
    public static Response waitForTerminalState(String executionArn) {
        return await("execution " + executionArn + " to leave RUNNING")
                .atMost(TERMINAL_STATE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> describeExecution(executionArn),
                        response -> !"RUNNING".equals(response.jsonPath().getString("status")));
    }
}
