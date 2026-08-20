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
 * Integration tests for CloudTrail's tagging trio (lex00/floci#77): {@code AddTags},
 * {@code RemoveTags}, {@code ListTags}. Previously entirely unimplemented - every
 * {@code aws_cloudtrail} resource failed immediately after a successful {@code CreateTrail}
 * because the AWS provider's post-create Read always calls {@code ListTags}, regardless of
 * whether the config sets any tags at all.
 */
@QuarkusTest
class CloudTrailTagsIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listTagsOnAFreshlyCreatedUntaggedTrailSucceedsWithAnEmptyList() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "untagged-" + suffix;
        String bucket = "untagged-logs-" + suffix;
        createBucket(bucket);

        String trailArn = createTrail(trailName, bucket, null);

        // This is the exact call the AWS provider makes on every post-create Read, tagged or not
        // (lex00/floci#77) - it used to 400 with InvalidAction before any tagging arm existed.
        invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
            .then()
                .statusCode(200)
                .body(containsString("\"ResourceId\":\"" + trailArn + "\""))
                .body(containsString("\"TagsList\":[]"));
    }

    @Test
    void tagsSetOnCreateTrailRoundTripThroughListTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "created-tagged-" + suffix;
        String bucket = "created-tagged-logs-" + suffix;
        createBucket(bucket);

        String trailArn = createTrail(trailName, bucket, """
                [{"Key":"tofu-estate","Value":"crossing"},{"Key":"tofu-address","Value":"aws_cloudtrail.main"}]
                """);

        invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
            .then()
                .statusCode(200)
                .body(containsString("\"Key\":\"tofu-estate\""))
                .body(containsString("\"Value\":\"crossing\""))
                .body(containsString("\"Key\":\"tofu-address\""))
                .body(containsString("\"Value\":\"aws_cloudtrail.main\""));
    }

    @Test
    void addTagsAfterCreateThenRemoveOneRoundTripsThroughListTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "incremental-" + suffix;
        String bucket = "incremental-logs-" + suffix;
        createBucket(bucket);
        String trailArn = createTrail(trailName, bucket, null);

        // The shape choudoufu's own live-import stamp uses against a trail it did not create:
        // a plain AddTags call naming the trail's ARN, no CreateTrail involved.
        invokeCloudTrail("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"Environment","Value":"prod"},{"Key":"Owner","Value":"platform"}]}
                """, trailArn))
            .then().statusCode(200);

        String afterAdd = invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
            .then().statusCode(200).extract().asString();
        assertTrue(afterAdd.contains("\"Key\":\"Environment\"") && afterAdd.contains("\"Value\":\"prod\""),
                "Expected Environment=prod in " + afterAdd);
        assertTrue(afterAdd.contains("\"Key\":\"Owner\"") && afterAdd.contains("\"Value\":\"platform\""),
                "Expected Owner=platform in " + afterAdd);

        invokeCloudTrail("RemoveTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"Owner"}]}
                """, trailArn))
            .then().statusCode(200);

        String afterRemove = invokeCloudTrail("ListTags", String.format("""
                {"ResourceIdList":["%s"]}
                """, trailArn))
            .then().statusCode(200).extract().asString();
        assertTrue(afterRemove.contains("\"Key\":\"Environment\""),
                "Environment must survive removing a different key, got: " + afterRemove);
        assertTrue(!afterRemove.contains("\"Key\":\"Owner\""),
                "Owner must be gone after RemoveTags, got: " + afterRemove);
    }

    @Test
    void listTagsOnAnUnknownArnIsResourceNotFound() {
        invokeCloudTrail("ListTags", """
                {"ResourceIdList":["arn:aws:cloudtrail:us-east-1:000000000000:trail/does-not-exist"]}
                """)
            .then()
                .statusCode(400)
                .body(containsString("ResourceNotFoundException"));
    }

    // --- Helpers ---

    /** Creates a trail (optionally with an inline TagsList) and returns its TrailARN. */
    private static String createTrail(String name, String bucket, String tagsListJson) {
        String body = tagsListJson == null
                ? String.format("{\"Name\":\"%s\",\"S3BucketName\":\"%s\"}", name, bucket)
                : String.format("{\"Name\":\"%s\",\"S3BucketName\":\"%s\",\"TagsList\":%s}",
                        name, bucket, tagsListJson);
        return invokeCloudTrail("CreateTrail", body)
            .then()
                .statusCode(200)
                .extract().jsonPath().getString("TrailARN");
    }

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
}
