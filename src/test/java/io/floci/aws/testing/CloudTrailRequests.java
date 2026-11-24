package io.floci.aws.testing;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** CloudTrail's JSON 1.1 calls, as the integration tests send them: the action in {@code X-Amz-Target}. */
public final class CloudTrailRequests {

    private CloudTrailRequests() {
    }

    public static Response invokeCloudTrail(String action, String body) {
        return invokeCloudTrail(null, action, body);
    }

    /** As {@link #invokeCloudTrail(String, String)}, signed with {@code authorization} when it is not null. */
    public static Response invokeCloudTrail(String authorization, String action, String body) {
        RequestSpecification request = given()
                .header("X-Amz-Target", "CloudTrail_20131101." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return request.when().post("/");
    }
}
