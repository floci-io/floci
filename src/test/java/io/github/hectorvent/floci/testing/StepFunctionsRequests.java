package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** One Step Functions JSON-protocol call in the default Region. */
public final class StepFunctionsRequests {
    private StepFunctionsRequests() {}

    /** Posts {@code body}, serialized as a JSON object, as the {@code action} request. */
    public static Response sfn(String action, Map<String, ?> body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AWSStepFunctions." + action)
                .body(body).when().post("/");
    }
}
