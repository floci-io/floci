package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for AdvancedEventSelectors: the trail's S3 data-event capture decision
 * must be driven by the advanced selector, not the basic one, once advanced selectors are set.
 * This is the reproduction case for the CloudTrail circular-logging cost problem: excluding the
 * trail's own destination bucket via {@code resources.ARN notStartsWith} stops the trail from
 * recording its own delivery writes.
 */
@QuarkusTest
class CloudTrailAdvancedSelectorsIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @Inject
    CloudTrailLogWriter writer;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void advancedSelectorsExcludeLogBucketButCaptureOthers() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String logBucket = "log-bucket-" + suffix;
        String otherBucket = "other-bucket-" + suffix;
        String destBucket = "audit-dest-" + suffix;
        String trailName = "adv-" + suffix;

        createBucket(logBucket);
        createBucket(otherBucket);
        createBucket(destBucket);

        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200);

        String putAdvanced = String.format("""
                {"TrailName":"%s","AdvancedEventSelectors":[
                  {"Name":"exclude-log-bucket","FieldSelectors":[
                    {"Field":"eventCategory","Equals":["Data"]},
                    {"Field":"resources.type","Equals":["AWS::S3::Object"]},
                    {"Field":"resources.ARN","NotStartsWith":["arn:aws:s3:::%s/"]}
                  ]}
                ]}
                """, trailName, logBucket);
        invokeCloudTrail("PutEventSelectors", putAdvanced)
                .then().statusCode(200)
                .body(containsString("AdvancedEventSelectors"));

        invokeCloudTrail("StartLogging", String.format("{\"Name\":\"%s\"}", trailName))
                .then().statusCode(200);

        putObject(logBucket, "some-key", "excluded");
        putObject(otherBucket, "key", "captured");

        writer.flushNow();

        String listingXml = given()
                .when().get("/" + destBucket + "?list-type=2")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(listingXml.contains(".json.gz"),
                "Expected a delivered log file for the captured (other-bucket) event:\n" + listingXml);

        String logKey = extractFirstKey(listingXml);
        byte[] gz = given().when().get("/" + destBucket + "/" + logKey)
                .then().statusCode(200).extract().asByteArray();
        String json = gunzip(gz);

        assertTrue(json.contains(otherBucket), "Expected other-bucket event to be captured:\n" + json);
        assertFalse(json.contains(logBucket), "Expected log-bucket event to be excluded:\n" + json);
    }

    @Test
    void basicSelectorsStillCaptureAllS3DataEvents() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "basic-source-" + suffix;
        String destBucket = "basic-dest-" + suffix;
        String trailName = "basic-" + suffix;

        createBucket(sourceBucket);
        createBucket(destBucket);

        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200);

        String putBasic = String.format("""
                {"TrailName":"%s","EventSelectors":[
                  {"ReadWriteType":"All","DataResources":[
                    {"Type":"AWS::S3::Object","Values":["arn:aws:s3:::%s/"]}
                  ]}
                ]}
                """, trailName, sourceBucket);
        invokeCloudTrail("PutEventSelectors", putBasic)
                .then().statusCode(200)
                .body(containsString("EventSelectors"));

        invokeCloudTrail("StartLogging", String.format("{\"Name\":\"%s\"}", trailName))
                .then().statusCode(200);

        putObject(sourceBucket, "hello.txt", "hello");
        writer.flushNow();

        String listingXml = given()
                .when().get("/" + destBucket + "?list-type=2")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(listingXml.contains(".json.gz"), "Basic selector should still capture events:\n" + listingXml);
    }

    @Test
    void puttingBothBasicAndAdvancedSelectorsErrors() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String destBucket = "both-dest-" + suffix;
        String trailName = "both-" + suffix;

        createBucket(destBucket);
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
                .then().statusCode(200);

        String putBoth = String.format("""
                {"TrailName":"%s",
                 "EventSelectors":[{"ReadWriteType":"All"}],
                 "AdvancedEventSelectors":[{"Name":"x","FieldSelectors":[
                    {"Field":"eventCategory","Equals":["Data"]}]}]}
                """, trailName);
        invokeCloudTrail("PutEventSelectors", putBoth)
                .then().statusCode(400)
                .body(containsString("InvalidParameterCombinationException"));
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

    private static void putObject(String bucket, String key, String body) {
        given().body(body)
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private static String extractFirstKey(String xml) {
        int open = xml.indexOf("<Key>");
        if (open < 0) return null;
        int close = xml.indexOf("</Key>", open);
        return xml.substring(open + 5, close);
    }

    private static String gunzip(byte[] gz) throws Exception {
        try (var gzin = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            return new String(gzin.readAllBytes());
        }
    }
}
