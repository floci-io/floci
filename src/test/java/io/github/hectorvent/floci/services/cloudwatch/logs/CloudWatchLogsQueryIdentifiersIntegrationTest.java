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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchLogsQueryIdentifiersIntegrationTest {

    private String region;
    private String group;
    private String describedArn;
    private boolean created;
    private long timestamp;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteOwnedGroup() {
        if (created) {
            logs("DeleteLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,name", "us-east-1,arn", "us-east-1,wildcard", "us-east-1,colon",
            "us-gov-west-1,colon", "cn-north-1,colon"})
    void queryResolvesTheSameGroupForEachIdentifierForm(String requestRegion, String form) {
        String arn = seed(requestRegion);
        String identifier = switch (form) {
            case "name" -> group;
            case "arn" -> arn;
            case "wildcard" -> arn + ":*";
            case "colon" -> describedArn.replace("*", "");
            default -> throw new IllegalArgumentException(form);
        };

        assertSingleEvent(List.of(identifier));
    }

    @Test
    void equivalentIdentifiersDoNotScanTheSameGroupTwice() {
        String arn = seed("us-east-1");

        assertSingleEvent(List.of(describedArn.replace("*", ""), arn, describedArn, group));
    }

    @Test
    void aPlainNameWithATrailingColonIsNotSilentlyRewritten() {
        seed("us-east-1");

        startQuery(List.of(group + ":")).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The specified log group does not exist."));
    }

    @Test
    void aNonArnLogGroupMarkerIsNotSilentlyRewritten() {
        seed("us-east-1");

        startQuery(List.of("not-an-arn:log-group:" + group + ":")).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The specified log group does not exist."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "wrong-service", "wildcard-colon"})
    void malformedIdentifiersAreNotSilentlyRewritten(String form) {
        String arn = seed("us-east-1");
        String identifier = switch (form) {
            case "malformed" -> "arn:bogus:log-group:" + group + ":";
            case "wrong-service" -> arn.replace(":logs:", ":s3:") + ":";
            case "wildcard-colon" -> arn + ":*:";
            default -> throw new IllegalArgumentException(form);
        };

        startQuery(List.of(identifier)).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn::logs:::", "arn::logs:us-east-1:000000000000:",
            "arn:bogus:logs:us-east-1:000000000000:", "arn:aws:logs::000000000000:",
            "arn:aws:logs: :000000000000:", "arn:aws:logs:us east 1:000000000000:",
            "arn:aws:logs:us-east-1::", "arn:aws:logs:us-east-1:123:",
            "arn:aws:logs:us-east-1:abcdefghijkl:"})
    void incompleteOrInvalidArnFieldsAreNotSilentlyRewritten(String prefix) {
        seed("us-east-1");

        startQuery(List.of(prefix + "log-group:" + group + ":")).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"logGroupName", "logGroupNames"})
    void nameSelectorsDoNotAcceptTheTrailingColonArnTolerance(String selector) {
        seed("us-east-1");
        String identifier = describedArn.replace("*", "");
        Object value = "logGroupName".equals(selector) ? identifier : List.of(identifier);

        logs("StartQuery", Map.of(selector, value, "startTime", timestamp / 1000 - 1,
                "endTime", timestamp / 1000 + 1, "queryString", "fields @message | limit 2"))
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @CsvSource({"logGroupName,name", "logGroupName,arn", "logGroupName,wildcard",
            "logGroupNames,name", "logGroupNames,arn", "logGroupNames,wildcard"})
    void nameSelectorsKeepTheirExistingIdentifierForms(String selector, String form) {
        String arn = seed("us-east-1");
        String identifier = switch (form) {
            case "name" -> group;
            case "arn" -> arn;
            case "wildcard" -> describedArn;
            default -> throw new IllegalArgumentException(form);
        };
        Object value = "logGroupName".equals(selector) ? identifier : List.of(identifier);

        String queryId = logs("StartQuery", Map.of(selector, value, "startTime", timestamp / 1000 - 1,
                "endTime", timestamp / 1000 + 1, "queryString", "fields @message | limit 2"))
                .then().statusCode(200).extract().path("queryId");
        assertSingleEvent(queryId);
    }

    @Test
    void anArnForAnAbsentGroupStillFails() {
        String arn = seed("us-east-1");

        startQuery(List.of(arn + "-absent:")).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private String seed(String requestRegion) {
        region = requestRegion;
        group = "/floci/query-identifiers/" + UUID.randomUUID();
        logs("CreateLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        created = true;
        logs("CreateLogStream", Map.of("logGroupName", group, "logStreamName", "stream"))
                .then().statusCode(200);
        timestamp = System.currentTimeMillis();
        logs("PutLogEvents", Map.of("logGroupName", group, "logStreamName", "stream",
                "logEvents", List.of(Map.of("timestamp", timestamp, "message", "identifier-fixture"))))
                .then().statusCode(200);
        Response groups = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", group))
                .then().statusCode(200).extract().response();
        describedArn = groups.jsonPath().getString("logGroups[0].arn");
        return groups.jsonPath().getString("logGroups[0].logGroupArn");
    }

    private void assertSingleEvent(List<String> identifiers) {
        String queryId = startQuery(identifiers).then().statusCode(200).extract().path("queryId");
        assertSingleEvent(queryId);
    }

    private void assertSingleEvent(String queryId) {
        Response results = logs("GetQueryResults", Map.of("queryId", queryId)).then().statusCode(200)
                .body("status", equalTo("Complete"))
                .body("results", hasSize(1))
                .body("results[0].find { it.field == '@message' }.value", equalTo("identifier-fixture"))
                .extract().response();
        assertEquals(1.0, results.jsonPath().getDouble("statistics.recordsScanned"));
    }

    private Response startQuery(List<String> identifiers) {
        return logs("StartQuery", Map.of("logGroupIdentifiers", identifiers,
                "startTime", timestamp / 1000 - 1, "endTime", timestamp / 1000 + 1,
                "queryString", "fields @message | limit 2"));
    }

    private Response logs(String action, Map<String, ?> body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261019/" + region + "/logs/aws4_request")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(body).post("/");
    }
}
