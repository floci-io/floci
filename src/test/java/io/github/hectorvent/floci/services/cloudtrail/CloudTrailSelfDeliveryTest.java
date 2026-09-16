package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces issue 0034: a trail whose blanket S3 data-event selector matches
 * its own destination bucket must see its own log deliveries as PutObject
 * data events (the circular-logging bug PR #1194 / upstream issue #1192
 * describe). {@link CloudTrailLogWriter} writes deliveries via a direct
 * {@code S3Service} call, which must still be visible to CloudTrail's own
 * data-event capture, not just API-driven writes.
 */
@QuarkusTest
class CloudTrailSelfDeliveryTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @Inject
    CloudTrailLogWriter writer;

    @Test
    void trailWithBlanketSelectorCapturesItsOwnLogDeliveryAsDataEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "loop-logs-" + suffix;
        String trailName = "loop-trail-" + suffix;

        createBucket(bucket);

        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, bucket))
            .then().statusCode(200);

        // Blanket selector: every S3 object in every bucket, including the
        // trail's own delivery bucket. This is the pathological "before"
        // config (uc-build-loop): s3DataEvents true, no exclusions.
        invokeCloudTrail("PutEventSelectors", String.format("""
                {
                  "TrailName": "%s",
                  "EventSelectors": [
                    {
                      "ReadWriteType": "All",
                      "IncludeManagementEvents": false,
                      "DataResources": [
                        {"Type": "AWS::S3::Object", "Values": ["arn:aws:s3:::"]}
                      ]
                    }
                  ]
                }
                """, trailName))
            .then().statusCode(200);

        invokeCloudTrail("StartLogging",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        // Seed one real data event so the first flush has something to
        // deliver.
        putObject(bucket, "seed.txt", "seed");

        // Delivery #1: writes a log file into `bucket`. Under the fix, that
        // write is itself a PutObject matching the blanket selector, so it
        // must be queued for delivery #2.
        writer.flushNow();

        // Delivery #2: should contain a record describing delivery #1's own
        // log-file write, the self-referential loop.
        writer.flushNow();

        List<String> keys = new ArrayList<>();
        for (String key : listKeys(bucket)) {
            if (key.contains("/CloudTrail/") && key.endsWith(".json.gz")) {
                keys.add(key);
            }
        }
        assertTrue(keys.size() >= 2,
                "Expected at least 2 delivered log files (seed delivery + self-referential delivery), got: "
                        + keys);

        boolean sawSelfReferentialPut = false;
        ObjectMapper mapper = new ObjectMapper();
        for (String key : keys) {
            byte[] gz = given().when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            byte[] json;
            try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = gzin.readAllBytes();
            }
            JsonNode records = mapper.readTree(json).get("Records");
            for (JsonNode rec : records) {
                String eventName = rec.path("eventName").asText();
                String recBucket = rec.path("requestParameters").path("bucketName").asText(null);
                String recKey = rec.path("requestParameters").path("key").asText("");
                if ("PutObject".equals(eventName) && bucket.equals(recBucket)
                        && recKey.contains("/CloudTrail/")) {
                    sawSelfReferentialPut = true;
                }
            }
        }

        assertTrue(sawSelfReferentialPut,
                "Expected a PutObject record for the trail's own CloudTrail log delivery "
                        + "(self-referential / circular-logging reproduction) but found none in "
                        + keys.size() + " delivered files");
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

    private static List<String> listKeys(String bucket) {
        String xml = given().when().get("/" + bucket + "?list-type=2")
                .then().statusCode(200).extract().asString();
        List<String> keys = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = xml.indexOf("<Key>", from);
            if (open < 0) break;
            int close = xml.indexOf("</Key>", open);
            keys.add(xml.substring(open + 5, close));
            from = close + 6;
        }
        return keys;
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
}
