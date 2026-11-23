package io.floci.aws.services.cloudtrail;

import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

/** CloudTrail's JSON 1.1 calls, as the integration tests send them: the action in {@code X-Amz-Target}. */
final class CloudTrailRequests {

    private CloudTrailRequests() {
    }

    static Response invokeCloudTrail(String action, String body) {
        return given()
                .header("X-Amz-Target", "CloudTrail_20131101." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
