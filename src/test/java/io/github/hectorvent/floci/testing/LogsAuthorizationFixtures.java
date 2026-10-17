package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Shared signed requests and IAM users for Logs authorization tests. */
public final class LogsAuthorizationFixtures {
    private LogsAuthorizationFixtures() {
    }

    public static String user(PartitionCleanup cleanup, int port, String account, String prefix,
                              String policyName, String statements) {
        String name = prefix + UUID.randomUUID();
        String root = EnforcementFixtures.auth(account, "iam");
        String key = EnforcementFixtures.createUser(cleanup, port, root, name).accessKeyId();
        given().port(port).header("Authorization", root).formParam("Action", "PutUserPolicy")
                .formParam("UserName", name).formParam("PolicyName", policyName)
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}")
                .when().post("/").then().statusCode(200);
        return key;
    }

    public static Response call(int port, String caller, String region, String action, Map<String, ?> body) {
        return call(port, caller, "20261015", region, action, body);
    }

    public static Response call(int port, String caller, String date, String region, String action, Map<String, ?> body) {
        String authorization = EnforcementFixtures.auth(caller, date, region, "logs");
        return given().port(port).contentType("application/x-amz-json-1.1")
                .header("Authorization", authorization).header("X-Amz-Target", "Logs_20140328." + action)
                .body(new LinkedHashMap<>(body)).when().post("/");
    }

    public static Map<String, Object> tagBody(String action, String resource) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceArn", resource);
        if (action.equals("TagResource")) {
            body.put("tags", Map.of("team", "red"));
        } else if (action.equals("UntagResource")) {
            body.put("tagKeys", List.of("team"));
        }
        return body;
    }

    public static Map<String, String> expectedTags(String action) {
        return switch (action) {
            case "TagResource" -> Map.of("team", "red");
            case "UntagResource" -> Map.of();
            case "ListTagsForResource" -> Map.of("team", "blue");
            default -> throw new IllegalArgumentException(action);
        };
    }
}
