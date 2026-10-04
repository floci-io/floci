package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The durable execution routes and the Invoke rules that need no running function: routing of
 * raw and percent-encoded ARNs, request validation, and the errors AWS returns for them.
 */
@QuarkusTest
class LambdaDurableExecutionApiIntegrationTest {

    @Inject
    LambdaService lambdaService;

    private static final String LAMBDA = "/2015-03-31";
    private static final String DURABLE = "/2025-12-01";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:api-durable-fn";
    private static final String UNKNOWN_EXECUTION = FUNCTION_ARN
            + ":1/durable-execution/missing/00000000-0000-0000-0000-000000000000";
    private static final String ENCODED_UNKNOWN_EXECUTION = "arn%3Aaws%3Alambda%3Aus-east-1%3A000000000000%3A"
            + "function%3Aapi-durable-fn%3A%24LATEST%2Fdurable-execution%2Fmissing%2F"
            + "00000000-0000-0000-0000-000000000000";

    @BeforeEach
    void createFunctions() {
        createFunction("api-durable-fn", ", \"DurableConfig\": {\"ExecutionTimeout\": 3600}");
        createFunction("api-long-durable-fn", ", \"DurableConfig\": {\"ExecutionTimeout\": 901}");
        createFunction("api-plain-fn", "");
    }

    /** Idempotent: the functions stay for the whole class, so a repeat create answers 409. */
    private static void createFunction(String name, String members) {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"%s
                }
                """.formatted(name, members))
        .when()
            .post(LAMBDA + "/functions")
        .then()
            .statusCode(anyOf(is(201), is(409)));
    }

    /** RestAssured must not re-encode the path: both the raw and the SDK's percent-encoded form go on the wire as is. */
    private static RequestSpecification raw() {
        return given().urlEncodingEnabled(false);
    }

    @Test
    void rawAndEncodedExecutionArnsReachTheSameRoute() {
        raw().get(DURABLE + "/durable-executions/" + UNKNOWN_EXECUTION)
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Durable Execution does not exist"));

        raw().get(DURABLE + "/durable-executions/" + ENCODED_UNKNOWN_EXECUTION)
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        raw().get(DURABLE + "/durable-executions/" + ENCODED_UNKNOWN_EXECUTION + "/history")
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        raw().get(DURABLE + "/durable-executions/" + ENCODED_UNKNOWN_EXECUTION + "/history?MaxItems=-1")
            .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '-1' at 'maxItems' failed to satisfy "
                    + "constraint: Member must have value greater than or equal to 0"));

        raw().contentType("application/json").body("{\"CheckpointToken\": \"QUJD\", \"Updates\": []}")
            .post(DURABLE + "/durable-executions/" + UNKNOWN_EXECUTION + "/checkpoint")
            .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("Invalid checkpoint token"));
    }

    @Test
    void aMalformedExecutionArnIsAValidationError() {
        raw().get(DURABLE + "/durable-executions/" + FUNCTION_ARN)
            .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", startsWith("1 validation error detected: Value '" + FUNCTION_ARN
                    + "' at 'durableExecutionArn' failed to satisfy constraint: Member must satisfy regular "
                    + "expression pattern: arn:([a-zA-Z0-9-]+):lambda:"));
    }

    @Test
    void aDurableFunctionNeedsAQualifiedReference() {
        for (String invocationType : new String[] {"RequestResponse", "DryRun"}) {
            given()
                .header("X-Amz-Invocation-Type", invocationType)
                .body("{}")
            .when()
                .post(LAMBDA + "/functions/api-durable-fn/invocations")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("You cannot invoke a durable function using an unqualified ARN."));
        }

        given()
            .header("X-Amz-Invocation-Type", "DryRun")
            .body("{}")
        .when()
            .post(LAMBDA + "/functions/api-durable-fn/invocations?Qualifier=$LATEST")
        .then()
            .statusCode(204);
    }

    @Test
    void anArnInvocationOfADurableFunctionNeedsAQualifierToo() {
        AwsException rejected = assertThrows(AwsException.class, () -> lambdaService.invokeArn(
                FUNCTION_ARN, "{}".getBytes(StandardCharsets.UTF_8), InvocationType.Event));
        assertEquals("InvalidParameterValueException", rejected.getErrorCode());
        assertEquals("You cannot invoke a durable function using an unqualified ARN.", rejected.getMessage());
    }

    @Test
    void aSynchronousInvokeNeedsAnExecutionTimeoutWithinTheInvocationLimit() {
        given()
            .body("{}")
        .when()
            .post(LAMBDA + "/functions/api-long-durable-fn:$LATEST/invocations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("You cannot synchronously invoke a durable function with an executionTimeout "
                    + "greater than 15 minutes."));
    }

    @Test
    void anInvalidExecutionNameIsRejected() {
        given()
            .header("X-Amz-Invocation-Type", "Event")
            .header("X-Amz-Durable-Execution-Name", "has space")
            .body("{}")
        .when()
            .post(LAMBDA + "/functions/api-durable-fn:$LATEST/invocations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("'durableExecutionName'"));
    }

    @Test
    void listValidatesItsParametersAndTheFunction() {
        given()
        .when()
            .get(DURABLE + "/functions/api-durable-fn/durable-executions")
        .then()
            .statusCode(200)
            .body("DurableExecutions", empty());

        given()
        .when()
            .get(DURABLE + "/functions/api-durable-fn/durable-executions?Statuses=PAUSED")
        .then()
            .statusCode(200)
            .body("DurableExecutions", empty());

        given()
        .when()
            .get(DURABLE + "/functions/api-durable-fn/durable-executions?Statuses=PAUSED&Marker=bogus")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("Invalid Marker"));

        given()
        .when()
            .get(DURABLE + "/functions/api-plain-fn/durable-executions?Statuses=BOGUS")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", startsWith("1 validation error detected: Value '[BOGUS]' at 'statuses'"));

        given()
        .when()
            .get(DURABLE + "/functions/api-durable-fn/durable-executions?StartedAfter=NaN")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("'NaN' can not be converted to Date"));

        given()
        .when()
            .get(DURABLE + "/functions/no-such-durable-fn/durable-executions?Statuses=RUNNING&Statuses=FAILED")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("Cannot filter by more than one status"));

        given()
        .when()
            .get(DURABLE + "/functions/no-such-durable-fn/durable-executions?Statuses=BOGUS&MaxItems=-1")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", startsWith("2 validation errors detected: Value '-1' at 'maxItems' failed to satisfy "
                    + "constraint: Member must have value greater than or equal to 0; Value '[BOGUS]' at 'statuses'"));

        given()
        .when()
            .get(DURABLE + "/functions/api-durable-fn/durable-executions?MaxItems=x")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("'x' can not be converted to Integer"));

        given()
        .when()
            .get(DURABLE + "/functions/no-such-durable-fn/durable-executions")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
