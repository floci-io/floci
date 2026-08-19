package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code ScheduleLambdaFunction} against a <em>real</em> Lambda function — deployed
 * through Floci's own Lambda API and executed in the container Floci starts for it — rather
 * than a stub, so the whole path is exercised: decision to Lambda service to container to
 * history event.
 *
 * <p>Separate from {@link SwfIntegrationTest} because these cases are the only SWF tests that
 * need a Docker daemon; keeping them apart means a Docker-less run loses exactly this class
 * instead of the whole SWF wire suite.
 *
 * <p>The expected event sequence and field shapes were captured from the live service:
 * LambdaFunctionScheduled, LambdaFunctionStarted, then Completed carrying the function's
 * response as {@code result}, followed by a fresh DecisionTaskScheduled.
 */
@QuarkusTest
class SwfLambdaIntegrationTest {

    private static final String LAMBDA_PATH = "/2015-03-31/functions";
    private static final String ROLE = "arn:aws:iam::000000000000:role/swf-lambda-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response swf(String action, String body) {
        return given()
                .header("X-Amz-Target", "SimpleWorkflowService." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body)
                .when().post("/");
    }

    /** A python handler that echoes its event, so the response proves the input reached it. */
    private static String echoFunctionZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("handler.py"));
            zip.write(("import json\n"
                    + "def handler(event, context):\n"
                    + "    return {'echoed': event, 'ok': True}\n").getBytes());
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void createEchoFunction(String name) throws Exception {
        given().contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "Runtime": "python3.12",
                          "Role": "%s",
                          "Handler": "handler.handler",
                          "Timeout": 30,
                          "Code": {"ZipFile": "%s"}
                        }
                        """.formatted(name, ROLE, echoFunctionZip()))
                .when().post(LAMBDA_PATH)
                .then().statusCode(201)
                .body("State", equalTo("Active"));
    }

    /** Registers a domain plus a workflow type carrying {@code defaultLambdaRole}. */
    private static void setUpDomain(String domain, String taskList, String lambdaRole) {
        swf("RegisterDomain", """
                {"name": "%s", "workflowExecutionRetentionPeriodInDays": "1"}
                """.formatted(domain)).then().statusCode(200);

        String role = lambdaRole == null ? "" : "\"defaultLambdaRole\": \"%s\",".formatted(lambdaRole);
        swf("RegisterWorkflowType", """
                {"domain": "%s", "name": "LambdaWf", "version": "1.0",
                 %s
                 "defaultTaskList": {"name": "%s"},
                 "defaultTaskStartToCloseTimeout": "300",
                 "defaultExecutionStartToCloseTimeout": "900",
                 "defaultChildPolicy": "TERMINATE"}
                """.formatted(domain, role, taskList)).then().statusCode(200);
    }

    private static String startAndPoll(String domain, String workflowId, String taskList) {
        swf("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "%s",
                 "workflowType": {"name": "LambdaWf", "version": "1.0"}}
                """.formatted(domain, workflowId)).then().statusCode(200);

        return swf("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "%s"}, "identity": "decider"}
                """.formatted(domain, taskList))
                .then().statusCode(200)
                .extract().path("taskToken");
    }

    @Test
    void scheduleLambdaFunction_runsTheRealFunctionAndRecordsItsResponse() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String fn = "swf-echo-" + suffix;
        String domain = "swf-lambda-" + suffix;
        String taskList = "lam-tl-" + suffix;

        createEchoFunction(fn);
        setUpDomain(domain, taskList, ROLE);
        String token = startAndPoll(domain, "wf-lambda", taskList);

        swf("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "ScheduleLambdaFunction",
                    "scheduleLambdaFunctionDecisionAttributes": {
                      "id": "lam-1",
                      "name": "%s",
                      "control": "ctl-1",
                      "input": "{\\"hello\\": \\"swf\\"}",
                      "startToCloseTimeout": "30"
                    }}]}
                """.formatted(token, fn)).then().statusCode(200);

        Response history = swf("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-lambda"}}
                """.formatted(domain));

        history.then()
                .statusCode(200)
                .body("events.eventType", org.hamcrest.Matchers.hasItems(
                        "LambdaFunctionScheduled", "LambdaFunctionStarted", "LambdaFunctionCompleted"))
                .body("events.find { it.eventType == 'LambdaFunctionScheduled' }"
                        + ".lambdaFunctionScheduledEventAttributes.id", equalTo("lam-1"))
                .body("events.find { it.eventType == 'LambdaFunctionScheduled' }"
                        + ".lambdaFunctionScheduledEventAttributes.name", equalTo(fn))
                .body("events.find { it.eventType == 'LambdaFunctionScheduled' }"
                        + ".lambdaFunctionScheduledEventAttributes.control", equalTo("ctl-1"))
                .body("events.find { it.eventType == 'LambdaFunctionScheduled' }"
                        + ".lambdaFunctionScheduledEventAttributes.startToCloseTimeout", equalTo("30"))
                .body("events[-1].eventType", equalTo("DecisionTaskScheduled"));

        // The recorded result must be the function's actual output, which proves the container
        // ran and received the decider's input rather than the event being synthesised.
        String result = history.path(
                "events.find { it.eventType == 'LambdaFunctionCompleted' }"
                        + ".lambdaFunctionCompletedEventAttributes.result");
        assertTrue(result != null && result.contains("\"ok\""),
                "expected the echo handler's response, got: " + result);
        assertTrue(result.contains("swf"),
                "expected the decider's input echoed back, got: " + result);

        // initiated/started correlation ids must name the events they refer to.
        int scheduledId = history.path(
                "events.find { it.eventType == 'LambdaFunctionScheduled' }.eventId");
        int startedId = history.path(
                "events.find { it.eventType == 'LambdaFunctionStarted' }.eventId");
        assertEquals(scheduledId, (int) history.path(
                "events.find { it.eventType == 'LambdaFunctionStarted' }"
                        + ".lambdaFunctionStartedEventAttributes.scheduledEventId"));
        assertEquals(scheduledId, (int) history.path(
                "events.find { it.eventType == 'LambdaFunctionCompleted' }"
                        + ".lambdaFunctionCompletedEventAttributes.scheduledEventId"));
        assertEquals(startedId, (int) history.path(
                "events.find { it.eventType == 'LambdaFunctionCompleted' }"
                        + ".lambdaFunctionCompletedEventAttributes.startedEventId"));
    }

    @Test
    void scheduleLambdaFunction_forAMissingFunction_recordsFailedWithTheAwsErrorCode() {
        String suffix = String.valueOf(System.nanoTime());
        String domain = "swf-lambda-missing-" + suffix;
        String taskList = "lam-tl-" + suffix;

        setUpDomain(domain, taskList, ROLE);
        String token = startAndPoll(domain, "wf-missing", taskList);

        swf("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "ScheduleLambdaFunction",
                    "scheduleLambdaFunctionDecisionAttributes": {
                      "id": "lam-missing", "name": "swf-no-such-function-%s"
                    }}]}
                """.formatted(token, suffix)).then().statusCode(200);

        // The live service still records Started before Failed for an unresolvable function,
        // and surfaces Lambda's own error code as the reason.
        swf("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-missing"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("events.eventType", org.hamcrest.Matchers.hasItems(
                        "LambdaFunctionScheduled", "LambdaFunctionStarted", "LambdaFunctionFailed"))
                .body("events.find { it.eventType == 'LambdaFunctionFailed' }"
                        + ".lambdaFunctionFailedEventAttributes.reason",
                        equalTo("ResourceNotFoundException"))
                .body("events.find { it.eventType == 'LambdaFunctionFailed' }"
                        + ".lambdaFunctionFailedEventAttributes.details", notNullValue())
                .body("events[-1].eventType", equalTo("DecisionTaskScheduled"));
    }

    @Test
    void scheduleLambdaFunction_withNoLambdaRole_reportsAssumeRoleFailedAndNeverStarts() {
        String suffix = String.valueOf(System.nanoTime());
        String domain = "swf-lambda-norole-" + suffix;
        String taskList = "lam-tl-" + suffix;

        setUpDomain(domain, taskList, null);
        String token = startAndPoll(domain, "wf-norole", taskList);

        swf("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "ScheduleLambdaFunction",
                    "scheduleLambdaFunctionDecisionAttributes": {"id": "x", "name": "whatever"}}]}
                """.formatted(token)).then().statusCode(200);

        swf("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-norole"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("events.eventType", org.hamcrest.Matchers.hasItem("StartLambdaFunctionFailed"))
                // This is the one Lambda path that never reaches Started.
                .body("events.eventType",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("LambdaFunctionStarted")))
                .body("events.find { it.eventType == 'StartLambdaFunctionFailed' }"
                        + ".startLambdaFunctionFailedEventAttributes.cause", equalTo("ASSUME_ROLE_FAILED"))
                .body("events.find { it.eventType == 'StartLambdaFunctionFailed' }"
                        + ".startLambdaFunctionFailedEventAttributes.message",
                        equalTo("No IAM role is attached to the current workflow execution."))
                .body("events[-1].eventType", equalTo("DecisionTaskScheduled"));
    }
}
