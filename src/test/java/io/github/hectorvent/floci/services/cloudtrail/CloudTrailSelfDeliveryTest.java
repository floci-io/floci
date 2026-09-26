package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        List<JsonNode> selfDeliveries = selfDeliveryPutRecords("us-east-1", "arn:aws:s3:::");

        assertFalse(selfDeliveries.isEmpty(),
                "Expected a PutObject record for the trail's own CloudTrail log delivery "
                        + "(self-referential / circular-logging reproduction) but found none");
    }

    /**
     * The log writer emits its own deliveries from the flush thread, outside any request, so the
     * record's hosts and ARNs must come from the event's region: a China trail's delivery names a
     * China host and ARN, not the deployment default's.
     */
    @Test
    void selfDeliveryRecordNamesTheEventRegionsHostAndPartition() throws Exception {
        List<JsonNode> selfDeliveries = selfDeliveryPutRecords("cn-north-1", "arn:aws-cn:s3:::");

        assertFalse(selfDeliveries.isEmpty(), "no self-delivery record in cn-north-1");
        for (JsonNode record : selfDeliveries) {
            assertEquals("cn-north-1", record.path("awsRegion").asText());
            String bucket = record.path("requestParameters").path("bucketName").asText();
            assertEquals(bucket + ".s3.amazonaws.com.cn", record.path("requestParameters").path("Host").asText());
            assertEquals(bucket + ".s3.amazonaws.com.cn",
                    record.path("tlsDetails").path("clientProvidedHostHeader").asText());
            for (JsonNode resource : record.path("resources")) {
                assertTrue(resource.path("ARN").asText().startsWith("arn:aws-cn:s3:::" + bucket),
                        "resource ARN: " + resource);
            }
        }
    }

    /**
     * Creates a trail in {@code region} whose blanket S3 data-event selector matches its own
     * destination bucket (the pathological "before" config, uc-build-loop: s3DataEvents true, no
     * exclusions), flushes twice, and returns the PutObject records that describe the trail's own
     * log-file writes. Delivery #1 writes a log file into the bucket; under the fix that write is
     * itself a matching PutObject, so delivery #2 carries a record of it.
     */
    private List<JsonNode> selfDeliveryPutRecords(String region, String selectorArnPrefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "loop-logs-" + suffix;
        String trailName = "loop-trail-" + suffix;
        S3RequestSigner s3 = S3RequestSigner.signedAs("test", "test").inRegion(region);

        createBucket(bucket, s3);

        invokeCloudTrail(region, "CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, bucket))
            .then().statusCode(200);

        invokeCloudTrail(region, "PutEventSelectors", String.format("""
                {
                  "TrailName": "%s",
                  "EventSelectors": [
                    {
                      "ReadWriteType": "All",
                      "IncludeManagementEvents": false,
                      "DataResources": [
                        {"Type": "AWS::S3::Object", "Values": ["%s"]}
                      ]
                    }
                  ]
                }
                """, trailName, selectorArnPrefix))
            .then().statusCode(200);

        invokeCloudTrail(region, "StartLogging",
                String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        // Seed one real data event so the first flush has something to deliver.
        putObject(bucket, "seed.txt", "seed", s3);

        writer.flushNow();
        writer.flushNow();

        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> selfDeliveries = new ArrayList<>();
        for (String key : listKeys(bucket, s3)) {
            if (!key.contains("/CloudTrail/") || !key.endsWith(".json.gz")) {
                continue;
            }
            byte[] gz = given().filter(s3).when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            byte[] json;
            try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = gzin.readAllBytes();
            }
            for (JsonNode record : mapper.readTree(json).get("Records")) {
                String recBucket = record.path("requestParameters").path("bucketName").asText(null);
                String recKey = record.path("requestParameters").path("key").asText("");
                if ("PutObject".equals(record.path("eventName").asText()) && bucket.equals(recBucket)
                        && recKey.contains("/CloudTrail/")) {
                    selfDeliveries.add(record);
                }
            }
        }
        return selfDeliveries;
    }

    // --- Helpers ---

    private static Response invokeCloudTrail(String region, String action, String body) {
        return given()
            .header("X-Amz-Target", CT_TARGET + action)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region
                    + "/cloudtrail/aws4_request, SignedHeaders=host, Signature=abc")
            .contentType(JSON11)
            .body(body)
        .when().post("/");
    }

    private static void createBucket(String name, S3RequestSigner signer) {
        given().filter(signer).when().put("/" + name).then().statusCode(200);
    }

    private static void putObject(String bucket, String key, String body, S3RequestSigner signer) {
        given().filter(signer).body(body)
            .when().put("/" + bucket + "/" + key)
            .then().statusCode(200);
    }

    private static List<String> listKeys(String bucket, S3RequestSigner signer) {
        String xml = given().filter(signer).when().get("/" + bucket + "?list-type=2")
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
