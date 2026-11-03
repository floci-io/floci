package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.LogsRequests.logs;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudWatchLogsGroupPaginationIntegrationTest {

    private final String prefix = "/floci/group-pages/" + UUID.randomUUID() + "/";
    private final List<String> groups = new ArrayList<>();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteOwnedGroups() {
        for (String group : groups) {
            logs("DeleteLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        }
    }

    @Test
    void traversesAsciiSortedPagesWithoutRepeatingOrSkippingGroups() {
        createGroups(3);
        List<String> found = new ArrayList<>();
        String token = null;
        for (int page = 0; page < 3; page++) {
            Map<String, Object> request = new HashMap<>(Map.of("logGroupNamePrefix", prefix, "limit", 1));
            if (token != null) {
                request.put("nextToken", token);
            }
            Response response = logs("DescribeLogGroups", request).then().statusCode(200).extract().response();
            List<String> names = response.jsonPath().getList("logGroups.logGroupName", String.class);
            assertEquals(1, names.size());
            found.addAll(names);
            token = response.path("nextToken");
            if (page < 2) {
                assertNotNull(token);
            } else {
                assertFalse(response.jsonPath().getMap("$").containsKey("nextToken"));
            }
        }
        assertEquals(groups.stream().sorted().toList(), found);
    }

    @Test
    void defaultsToFiftyGroupsAndReturnsTheRemainder() {
        createGroups(51);
        Response first = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", prefix))
                .then().statusCode(200).extract().response();
        assertEquals(50, first.jsonPath().getList("logGroups").size());
        String token = first.path("nextToken");
        assertNotNull(token);
        Response last = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", prefix, "nextToken", token))
                .then().statusCode(200).extract().response();
        assertEquals(List.of(groups.stream().sorted().toList().get(50)),
                last.jsonPath().getList("logGroups.logGroupName", String.class));
        assertFalse(last.jsonPath().getMap("$").containsKey("nextToken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "1.5", "\"one\"", "999999999999999999999"})
    void rejectsInvalidLimits(String limit) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .body("{\"limit\":" + limit + "}").post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @ParameterizedTest
    @CsvSource({"0, greater than or equal to 1", "51, less than or equal to 50"})
    void reportsOutOfRangeLimitsWithSmithyValidation(int limit, String constraint) {
        logs("DescribeLogGroups", Map.of("limit", limit)).then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("1 validation error detected: Value '" + limit
                        + "' at 'limit' failed to satisfy constraint: Member must have value " + constraint));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"not-base64!\"", "\"d3Jvbmctb3BlcmF0aW9u\"", "\"\"", "123", "{}"})
    void rejectsInvalidTokens(String token) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .body("{\"nextToken\":" + token + "}").post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("The specified nextToken is invalid."));
    }

    @Test
    void reportsBadTokensLikeDescribeLogStreams() {
        createGroups(1);
        String group = groups.getFirst();
        logs("CreateLogStream", Map.of("logGroupName", group, "logStreamName", "stream"))
                .then().statusCode(200);
        String streamError = logs("DescribeLogStreams", Map.of("logGroupName", group, "nextToken", "bad!"))
                .then().statusCode(400).extract().path("message");
        logs("DescribeLogGroups", Map.of("nextToken", "bad!")).then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(streamError));
    }

    @Test
    void resumesAfterADeletedCursorAndHandlesEmptyResults() {
        createGroups(3);
        Response first = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", prefix, "limit", 1))
                .then().statusCode(200).extract().response();
        String cursorGroup = first.path("logGroups[0].logGroupName");
        String token = first.path("nextToken");
        assertNotNull(token);
        logs("DeleteLogGroup", Map.of("logGroupName", cursorGroup)).then().statusCode(200);
        groups.remove(cursorGroup);
        Response next = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", prefix, "nextToken", token))
                .then().statusCode(200).extract().response();
        assertEquals(groups.stream().sorted().toList(),
                next.jsonPath().getList("logGroups.logGroupName", String.class));
        assertFalse(next.jsonPath().getMap("$").containsKey("nextToken"));
        Response empty = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", prefix + "missing"))
                .then().statusCode(200).extract().response();
        assertEquals(List.of(), empty.jsonPath().getList("logGroups"));
        assertFalse(empty.jsonPath().getMap("$").containsKey("nextToken"));
    }

    private void createGroups(int count) {
        for (int i = count - 1; i >= 0; i--) {
            String group = prefix + String.format("%03d", i);
            logs("CreateLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
            groups.add(group);
        }
    }
}
