package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** Sends CloudWatch Logs JSON 1.1 requests, signed for {@code us-east-1} unless a Region is given. */
public final class LogsRequests {
    private LogsRequests() {}

    public static Response logs(String action, String body) {
        return logs("us-east-1", action, body);
    }

    public static Response logs(String action, Map<String, ?> body) {
        return request("us-east-1", action).body(body).post("/");
    }

    public static Response logs(String region, String action, String body) {
        return request(region, action).body(body).post("/");
    }

    private static RequestSpecification request(String region, String action) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20261103/" + region + "/logs/aws4_request")
                .header("X-Amz-Target", "Logs_20140328." + action);
    }
}
