package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for ListTrails, AddTags, and ListTags: the trail
 * discovery and tagging control-plane actions.
 */
@QuarkusTest
class CloudTrailListingAndTagsIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listTrailsIncludesCreatedTrailByNameArnAndHomeRegion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "list-" + suffix;
        String destBucket = "list-logs-" + suffix;

        createBucket(destBucket);
        String createBody = invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200)
                .extract().asString();
        String trailArn = extractField(createBody, "TrailARN");

        String listBody = invokeCloudTrail("ListTrails", "{}")
                .then().statusCode(200)
                .body(containsString(trailName))
                .body(containsString(trailArn))
                .body(containsString("us-east-1"))
                .extract().asString();

        assertTrue(listBody.contains("\"Name\":\"" + trailName + "\""),
                "Expected ListTrails to include trail name:\n" + listBody);
        assertTrue(listBody.contains("\"TrailARN\":\"" + trailArn + "\""),
                "Expected ListTrails to include trail ARN:\n" + listBody);
        assertTrue(listBody.contains("\"HomeRegion\":\"us-east-1\""),
                "Expected ListTrails to include home region:\n" + listBody);
    }

    @Test
    void addTagsThenListTagsRoundTripsByResourceId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "tag-" + suffix;
        String destBucket = "tag-logs-" + suffix;

        createBucket(destBucket);
        String createBody = invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200)
                .extract().asString();
        String trailArn = extractField(createBody, "TrailARN");

        invokeCloudTrail("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[
                  {"Key":"Environment","Value":"prod"},
                  {"Key":"Owner","Value":"security-team"}
                ]}
                """, trailArn))
                .then().statusCode(200);

        String listTagsBody = invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
                .then().statusCode(200)
                .body(containsString(trailArn))
                .body(containsString("Environment"))
                .body(containsString("prod"))
                .body(containsString("Owner"))
                .body(containsString("security-team"))
                .extract().asString();

        assertTrue(listTagsBody.contains("\"ResourceId\":\"" + trailArn + "\""),
                "Expected ListTags to key by resource id:\n" + listTagsBody);
    }

    @Test
    void addTagsMergesWithPreviouslyAddedTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "merge-" + suffix;
        String destBucket = "merge-logs-" + suffix;

        createBucket(destBucket);
        String createBody = invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200)
                .extract().asString();
        String trailArn = extractField(createBody, "TrailARN");

        invokeCloudTrail("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"Environment","Value":"prod"}]}
                """, trailArn))
                .then().statusCode(200);
        invokeCloudTrail("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"Owner","Value":"security-team"}]}
                """, trailArn))
                .then().statusCode(200);

        invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
                .then().statusCode(200)
                .body(containsString("Environment"))
                .body(containsString("Owner"));
    }

    @Test
    void addTagsRejectsMoreThanTwoHundredTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "toomany-" + suffix;
        String destBucket = "toomany-logs-" + suffix;

        createBucket(destBucket);
        String createBody = invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200)
                .extract().asString();
        String trailArn = extractField(createBody, "TrailARN");

        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                tags.append(",");
            }
            tags.append(String.format("{\"Key\":\"k%d\",\"Value\":\"v%d\"}", i, i));
        }

        invokeCloudTrail("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[%s]}
                """, trailArn, tags))
                .then().statusCode(400)
                .body(containsString("TagsLimitExceededException"));
    }

    // --- Helpers ---

    private static io.restassured.response.Response invokeCloudTrail(String action, String body) {
        return given()
                .header("X-Amz-Target", CT_TARGET + action)
                .contentType(JSON11)
                .body(body)
                .when().post("/");
    }

    private static void createBucket(String name) {
        given().when().put("/" + name).then().statusCode(200);
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int open = json.indexOf(marker);
        if (open < 0) return null;
        int start = open + marker.length();
        int close = json.indexOf('"', start);
        return json.substring(start, close);
    }
}
