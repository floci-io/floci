package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the direct {@code StartExecution} API's name semantics, exercising the real
 * JSON handler + service + {@code AwsExceptionMapper} path. An EXPRESS state machine accepts a reused
 * execution name (each start is its own execution with its own {@code express:} ARN); a STANDARD state
 * machine still rejects a name collision with a 400 {@code ExecutionAlreadyExists}. The no-clobber
 * (data-loss) proof is at the service level in {@code StepFunctionsServiceStartExecutionExpressTest};
 * this test deliberately does not call DescribeExecution/ListExecutions on an EXPRESS ARN, which AWS
 * does not support for EXPRESS.
 */
@QuarkusTest
class StepFunctionsExpressStartExecutionIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String PASS_DEFINITION =
            "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"End\":true}}}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void expressAcceptsReusedNameAsDistinctExecutions() {
        String smArn = createStateMachine("express-name-reuse", "EXPRESS", PASS_DEFINITION);

        Response first = startNamed(smArn, "again", "{}");
        first.then().statusCode(200);
        Response second = startNamed(smArn, "again", "{}");
        second.then().statusCode(200);

        String arn1 = first.jsonPath().getString("executionArn");
        String arn2 = second.jsonPath().getString("executionArn");
        assertNotEquals(arn1, arn2, "each EXPRESS start must be its own execution");
        assertTrue(arn1.startsWith("arn:aws:states:"), arn1);
        assertTrue(arn1.contains(":express:express-name-reuse:again:"), arn1);
        assertTrue(arn2.contains(":express:express-name-reuse:again:"), arn2);
    }

    @Test
    void standardRejectsReusedNameWithExecutionAlreadyExists() {
        String smArn = createStateMachine("standard-name-reuse-control", null, PASS_DEFINITION);

        // Same name + different input is a conflict for STANDARD whether the first is still running or
        // closed, so this control is deterministic regardless of the async execution's timing.
        startNamed(smArn, "again", "{\"n\":1}").then().statusCode(200);
        Response conflict = startNamed(smArn, "again", "{\"n\":2}");
        conflict.then().statusCode(400);
        assertTrue(conflict.asString().contains("ExecutionAlreadyExists"), conflict.asString());
    }

    private static String createStateMachine(String name, String type, String definition) {
        var typeField = type != null ? "\"type\": \"%s\",".formatted(type) : "";
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            %s
                            "roleArn": "%s",
                            "definition": %s
                        }
                        """.formatted(name, typeField, ROLE_ARN, quote(definition)))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private static Response startNamed(String smArn, String name, String input) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "name": "%s", "input": %s}
                        """.formatted(smArn, name, quote(input)))
                .when().post("/");
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
