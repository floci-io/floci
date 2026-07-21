package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the CloudTrail control plane + S3 hook + log
 * writer pipeline. Sets up a trail, exercises the five hooked S3 ops, forces
 * a flush, and verifies that a gzipped CloudTrail log file lands in the
 * destination bucket with the right record shape.
 */
@QuarkusTest
class CloudTrailIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @Inject
    CloudTrailLogWriter writer;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void s3DataEventsLandAsGzippedLogFilesInDestinationBucket() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "audit-source-" + suffix;
        String destBucket = "audit-logs-" + suffix;
        String trailName = "audit-trail-" + suffix;

        // 1. Buckets
        createBucket(sourceBucket);
        createBucket(destBucket);

        // 2. CreateTrail
        invokeCloudTrail("CreateTrail", String.format("""
                {
                  "Name": "%s",
                  "S3BucketName": "%s",
                  "IsMultiRegionTrail": true,
                  "IncludeGlobalServiceEvents": true,
                  "EnableLogFileValidation": true
                }
                """, trailName, destBucket))
            .then()
                .statusCode(200)
                .body(containsString("\"TrailARN\""));

        // 3. PutEventSelectors — match all S3 objects in the source bucket
        invokeCloudTrail("PutEventSelectors", String.format("""
                {
                  "TrailName": "%s",
                  "EventSelectors": [
                    {
                      "ReadWriteType": "All",
                      "IncludeManagementEvents": false,
                      "DataResources": [
                        {
                          "Type": "AWS::S3::Object",
                          "Values": ["arn:aws:s3:::%s/"]
                        }
                      ]
                    }
                  ]
                }
                """, trailName, sourceBucket))
            .then()
                .statusCode(200)
                .body(containsString("\"EventSelectors\""));

        // 4. StartLogging
        invokeCloudTrail("StartLogging",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then()
                .statusCode(200);

        // 5. Drive S3 traffic against the source bucket — covers all five
        //    hooked ops plus a denial path (NoSuchKey on a missing GetObject).
        putObject(sourceBucket, "documents/hello.txt", "hello-world");
        getObject(sourceBucket, "documents/hello.txt", 200);
        headObject(sourceBucket, "documents/hello.txt", 200);
        listObjects(sourceBucket);
        getObject(sourceBucket, "documents/missing.txt", 404);
        deleteObject(sourceBucket, "documents/hello.txt");

        // 6. Force the writer to flush — bypasses the scheduled cadence.
        writer.flushNow();

        // 7. List the destination bucket and find the log file.
        String listingXml = given()
            .when().get("/" + destBucket + "?list-type=2")
            .then().statusCode(200)
            .extract().asString();

        // Expect AWS-shaped key: AWSLogs/<account>/CloudTrail/<region>/yyyy/MM/dd/<file>.json.gz
        assertTrue(listingXml.contains("AWSLogs/"),
                "Destination bucket missing AWSLogs prefix; got:\n" + listingXml);
        assertTrue(listingXml.contains("/CloudTrail/"),
                "Destination bucket missing CloudTrail segment; got:\n" + listingXml);
        assertTrue(listingXml.contains(".json.gz"),
                "Destination bucket missing .json.gz file; got:\n" + listingXml);

        String logKey = extractFirstKey(listingXml);
        assertNotNull(logKey, "Could not parse first <Key> from listing:\n" + listingXml);

        // 8. Download the log file and decompress.
        byte[] gz = given()
            .when().get("/" + destBucket + "/" + logKey)
            .then().statusCode(200)
            .extract().asByteArray();

        byte[] json;
        try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            json = gzin.readAllBytes();
        }

        // 9. Verify record shape.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = mapper.readTree(json);
        JsonNode records = envelope.get("Records");
        assertNotNull(records, "Missing Records[] in log file:\n" + new String(json));
        assertTrue(records.isArray(), "Records is not an array");
        assertTrue(records.size() >= 5,
                "Expected at least 5 records (Put/Get/Head/List/Delete + denial), got " + records.size()
                        + ":\n" + new String(json));

        boolean sawPut = false, sawGet = false, sawDelete = false, sawNoSuchKey = false;
        for (JsonNode rec : records) {
            assertEquals("1.11", rec.path("eventVersion").asText(),
                    "Bad eventVersion: " + rec);
            assertEquals("s3.amazonaws.com", rec.path("eventSource").asText(),
                    "Bad eventSource: " + rec);
            assertEquals("Data", rec.path("eventCategory").asText(),
                    "Bad eventCategory: " + rec);
            assertEquals("AwsApiCall", rec.path("eventType").asText(),
                    "Bad eventType: " + rec);
            assertNotNull(rec.path("eventID").asText(null), "Missing eventID");
            assertNotNull(rec.path("requestID").asText(null), "Missing requestID");
            assertEquals("IAMUser", rec.path("userIdentity").path("type").asText(),
                    "Bad userIdentity.type: " + rec);

            String name = rec.path("eventName").asText();
            if ("PutObject".equals(name)) sawPut = true;
            if ("GetObject".equals(name) && rec.path("errorCode").isMissingNode()) sawGet = true;
            if ("DeleteObject".equals(name)) sawDelete = true;
            if ("NoSuchKey".equals(rec.path("errorCode").asText(null))) sawNoSuchKey = true;
        }
        assertTrue(sawPut, "Expected a PutObject record");
        assertTrue(sawGet, "Expected a successful GetObject record");
        assertTrue(sawDelete, "Expected a DeleteObject record");
        assertTrue(sawNoSuchKey, "Expected a GetObject NoSuchKey record");
    }

    @Test
    void lookupEventsReturnsEmptyEventPage() {
        invokeCloudTrail("LookupEvents", """
                {
                    "LookupAttributes": [
                        {"AttributeKey": "EventName", "AttributeValue": "CreateBucket"}
                    ],
                    "MaxResults": 10
                }
                """)
        .then()
            .statusCode(200)
            .body("Events", hasSize(0));
    }

    @Test
    void describeTrailsRoundTripsCreatedTrail() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "rt-" + suffix;
        String destBucket = "rt-logs-" + suffix;

        createBucket(destBucket);
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
            .then().statusCode(200);

        invokeCloudTrail("DescribeTrails",
                String.format("{\"trailNameList\":[\"%s\"]}", trailName))
            .then()
                .statusCode(200)
                .body(containsString("\"" + trailName + "\""))
                .body(containsString(destBucket));
    }

    @Test
    void getTrailStatusReflectsStartStopLogging() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "ss-" + suffix;
        String destBucket = "ss-logs-" + suffix;

        createBucket(destBucket);
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
            .then().statusCode(200);

        invokeCloudTrail("GetTrailStatus",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200).body(containsString("\"IsLogging\":false"));

        invokeCloudTrail("StartLogging",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        invokeCloudTrail("GetTrailStatus",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200).body(containsString("\"IsLogging\":true"));

        invokeCloudTrail("StopLogging",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        invokeCloudTrail("GetTrailStatus",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200).body(containsString("\"IsLogging\":false"));
    }

    @Test
    void deleteTrailRemovesItFromDescribeTrails() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "del-" + suffix;
        String destBucket = "del-logs-" + suffix;

        createBucket(destBucket);
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
            .then().statusCode(200);

        invokeCloudTrail("DescribeTrails",
                String.format("{\"trailNameList\":[\"%s\"]}", trailName))
            .then().statusCode(200).body(containsString(trailName));

        invokeCloudTrail("DeleteTrail",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        // After deletion, DescribeTrails with the same name yields an empty list
        String body = invokeCloudTrail("DescribeTrails",
                String.format("{\"trailNameList\":[\"%s\"]}", trailName))
            .then().statusCode(200).extract().asString();
        assertTrue(body.contains("\"trailList\":[]"),
                "Expected empty trailList after delete, got: " + body);
    }

    @Test
    void updateTrailReplacesS3BucketName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "upd-" + suffix;
        String oldBucket = "upd-old-" + suffix;
        String newBucket = "upd-new-" + suffix;

        createBucket(oldBucket);
        createBucket(newBucket);
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, oldBucket))
            .then().statusCode(200);

        invokeCloudTrail("UpdateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s","IsMultiRegionTrail":true}
                """, trailName, newBucket))
            .then().statusCode(200)
                .body(containsString("\"S3BucketName\":\"" + newBucket + "\""))
                .body(containsString("\"IsMultiRegionTrail\":true"));

        invokeCloudTrail("DescribeTrails",
                String.format("{\"trailNameList\":[\"%s\"]}", trailName))
            .then().statusCode(200)
                .body(containsString(newBucket));
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

    private static void getObject(String bucket, String key, int expectedStatus) {
        given().when().get("/" + bucket + "/" + key)
            .then().statusCode(expectedStatus);
    }

    private static void headObject(String bucket, String key, int expectedStatus) {
        given().when().head("/" + bucket + "/" + key)
            .then().statusCode(expectedStatus);
    }

    private static void listObjects(String bucket) {
        given().when().get("/" + bucket + "?list-type=2")
            .then().statusCode(200);
    }

    private static void deleteObject(String bucket, String key) {
        given().when().delete("/" + bucket + "/" + key)
            .then().statusCode(204);
    }

    /** Extract the first <Key>...</Key> from an S3 ListObjectsV2 XML response. */
    private static String extractFirstKey(String xml) {
        int open = xml.indexOf("<Key>");
        if (open < 0) return null;
        int close = xml.indexOf("</Key>", open);
        if (close < 0) return null;
        return xml.substring(open + "<Key>".length(), close);
    }
}
