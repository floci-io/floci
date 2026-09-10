package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudTrailLogWriterBoundedRetryIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final int RETRY_RECORD_LIMIT = 1024;
    private static final int PRODUCED_EVENTS = RETRY_RECORD_LIMIT + 80;

    @Inject
    CloudTrailLogWriter writer;

    @Inject
    CloudTrailService cloudTrailService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void retryStateStaysBoundedAndDeliversRetainedPrefixAfterDestinationRecovery() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "bounded-source-" + suffix;
        String destinationBucket = "bounded-logs-" + suffix;
        String trailName = "bounded-trail-" + suffix;
        String region = "us-east-1";

        createBucket(sourceBucket);
        createTrail(trailName, destinationBucket);
        selectSourceBucket(trailName, sourceBucket);
        startLogging(trailName);

        JsonNode startedStatus = getTrailStatus(trailName);
        assertFalse(startedStatus.has("LatestDeliveryTime"),
                "starting a trail must not claim that a delivery already happened: " + startedStatus);
        assertFalse(startedStatus.has("LatestDeliveryAttemptTime"),
                "GetTrailStatus must not expose the retired delivery-attempt field: " + startedStatus);

        for (int i = 0; i < PRODUCED_EVENTS; i++) {
            putObject(sourceBucket, eventKey(i), "event-" + i);
            if (i % 100 == 99) {
                writer.flushNow();
            }
        }
        writer.flushNow();

        JsonNode failedStatus = getTrailStatus(trailName);
        assertTrue(failedStatus.hasNonNull("LatestDeliveryError"),
                "failed S3 delivery must be visible through GetTrailStatus: " + failedStatus);
        assertTrue(failedStatus.path("LatestDeliveryError").asText().contains("NoSuchBucket"),
                "GetTrailStatus should expose the S3 delivery error: " + failedStatus);

        createBucket(destinationBucket);
        writer.flushNow();

        List<JsonNode> delivered = deliveredRecords(destinationBucket);
        assertEquals(RETRY_RECORD_LIMIT, delivered.size(),
                "only the retained retry prefix should be delivered after recovery");
        for (int i = 0; i < RETRY_RECORD_LIMIT; i++) {
            assertEquals(eventKey(i), delivered.get(i).path("requestParameters").path("key").asText());
        }
        assertFalse(delivered.stream().anyMatch(r ->
                        eventKey(RETRY_RECORD_LIMIT).equals(r.path("requestParameters").path("key").asText())),
                "events produced after the retry buffer filled should remain dropped");

        JsonNode recoveredStatus = getTrailStatus(trailName);
        assertFalse(recoveredStatus.has("LatestDeliveryError"),
                "successful delivery should clear LatestDeliveryError: " + recoveredStatus);
        assertTrue(recoveredStatus.hasNonNull("LatestDeliveryTime"),
                "successful delivery should update LatestDeliveryTime: " + recoveredStatus);
    }

    @Test
    void healthyBurstAboveRetryLimitIsDeliveredWithoutLoss() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "healthy-burst-source-" + suffix;
        String destinationBucket = "healthy-burst-logs-" + suffix;
        String trailName = "healthy-burst-trail-" + suffix;

        createBucket(sourceBucket);
        createBucket(destinationBucket);
        createTrail(trailName, destinationBucket);
        selectSourceBucket(trailName, sourceBucket);
        startLogging(trailName);

        for (int i = 0; i < PRODUCED_EVENTS; i++) {
            putObject(sourceBucket, eventKey(i), "event-" + i);
        }
        writer.flushNow();

        List<JsonNode> delivered = deliveredRecords(destinationBucket);
        assertEquals(PRODUCED_EVENTS, delivered.size(),
                "a healthy burst must not be limited by the retry budget");
        Set<String> deliveredKeys = new HashSet<>();
        for (JsonNode record : delivered) {
            deliveredKeys.add(record.path("requestParameters").path("key").asText());
        }
        for (int i = 0; i < PRODUCED_EVENTS; i++) {
            assertTrue(deliveredKeys.contains(eventKey(i)));
        }
    }

    @Test
    void retryStateStaysBoundedByBytes() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "bounded-bytes-source-" + suffix;
        String destinationBucket = "bounded-bytes-logs-" + suffix;
        String trailName = "bounded-bytes-trail-" + suffix;
        int producedEvents = 750;

        createBucket(sourceBucket);
        createTrail(trailName, destinationBucket);
        selectSourceBucket(trailName, sourceBucket);
        startLogging(trailName);

        String largeUserAgent = "x".repeat(6000);
        for (int i = 0; i < producedEvents; i++) {
            cloudTrailService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region("us-east-1")
                    .eventName("PutObject")
                    .bucketName(sourceBucket)
                    .key(eventKey(i))
                    .userAgent(largeUserAgent)
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
            if (i % 100 == 99) {
                writer.flushNow();
            }
        }
        writer.flushNow();

        createBucket(destinationBucket);
        writer.flushNow();

        List<JsonNode> delivered = deliveredRecords(destinationBucket);
        assertTrue(delivered.size() < producedEvents,
                "the byte limit must drop records before the record limit: " + delivered.size());
        assertTrue(delivered.size() > 0, "the byte-limited retry buffer should retain records");
        assertEquals(eventKey(0),
                delivered.get(0).path("requestParameters").path("key").asText());
    }

    @Test
    void retryRecordLimitIsSharedAcrossEventRegions() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "bounded-regions-source-" + suffix;
        String destinationBucket = "bounded-regions-logs-" + suffix;
        String trailName = "bounded-regions-trail-" + suffix;

        createBucket(sourceBucket);
        createTrail(trailName, destinationBucket, true);
        selectSourceBucket(trailName, sourceBucket);
        startLogging(trailName);

        emitEvents(sourceBucket, "us-east-1", 600, "east");
        writer.flushNow();
        emitEvents(sourceBucket, "us-west-2", 600, "west");
        writer.flushNow();

        createBucket(destinationBucket);
        writer.flushNow();

        List<JsonNode> delivered = deliveredRecords(destinationBucket);
        assertEquals(RETRY_RECORD_LIMIT, delivered.size(),
                "the record cap must apply to the trail, not each event region");
        assertTrue(delivered.stream().anyMatch(record -> "us-east-1".equals(record.path("awsRegion").asText())));
        assertTrue(delivered.stream().anyMatch(record -> "us-west-2".equals(record.path("awsRegion").asText())));
    }

    @Test
    void retryBudgetIncludesRecordsInFlightAcrossEventRegions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "in-flight-source-" + suffix;
        String destinationBucket = "in-flight-logs-" + suffix;
        String trailName = "in-flight-trail-" + suffix;

        createBucket(sourceBucket);
        createTrail(trailName, destinationBucket, true);
        selectSourceBucket(trailName, sourceBucket);
        startLogging(trailName);

        emitEvents(sourceBucket, "us-east-1", RETRY_RECORD_LIMIT, "east");
        CloudTrailService.TrailKey eastKey =
                new CloudTrailService.TrailKey("us-east-1", trailName, "us-east-1");
        List<ObjectNode> inFlight = cloudTrailService.drainPendingRecords(eastKey);

        emitEvents(sourceBucket, "us-west-2", 80, "west");
        cloudTrailService.requeueRecords(eastKey, inFlight);

        List<ObjectNode> retainedEast = cloudTrailService.drainPendingRecords(eastKey);
        List<ObjectNode> retainedWest = cloudTrailService.drainPendingRecords(
                new CloudTrailService.TrailKey("us-east-1", trailName, "us-west-2"));

        assertEquals(RETRY_RECORD_LIMIT, retainedEast.size(),
                "in-flight records must reserve the trail-wide retry budget");
        assertTrue(retainedWest.isEmpty(),
                "events from another region must not displace older in-flight records");
        for (int i = 0; i < RETRY_RECORD_LIMIT; i++) {
            assertEquals("east/" + eventKey(i),
                    retainedEast.get(i).path("requestParameters").path("key").asText());
        }
    }

    private static void createTrail(String trailName, String destinationBucket) {
        createTrail(trailName, destinationBucket, false);
    }

    private static void createTrail(String trailName, String destinationBucket, boolean multiRegion) {
        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s","IsMultiRegionTrail":%s}
                """, trailName, destinationBucket, multiRegion))
                .then().statusCode(200);
    }

    private static void selectSourceBucket(String trailName, String sourceBucket) {
        invokeCloudTrail("PutEventSelectors", String.format("""
                {
                  "TrailName": "%s",
                  "EventSelectors": [
                    {
                      "ReadWriteType": "All",
                      "IncludeManagementEvents": false,
                      "DataResources": [
                        {"Type": "AWS::S3::Object", "Values": ["arn:aws:s3:::%s/"]}
                      ]
                    }
                  ]
                }
                """, trailName, sourceBucket))
                .then().statusCode(200);
    }

    private static void startLogging(String trailName) {
        invokeCloudTrail("StartLogging", String.format("{\"Name\":\"%s\"}", trailName))
                .then().statusCode(200);
    }

    private static JsonNode getTrailStatus(String trailName) throws Exception {
        String body = invokeCloudTrail("GetTrailStatus", String.format("{\"Name\":\"%s\"}", trailName))
                .then().statusCode(200)
                .extract().asString();
        return new ObjectMapper().readTree(body);
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

    private static void putObject(String bucket, String key, String body) {
        given().body(body)
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private void emitEvents(String sourceBucket, String region, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            cloudTrailService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(region)
                    .eventName("PutObject")
                    .bucketName(sourceBucket)
                    .key(prefix + "/" + eventKey(i))
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
        }
    }

    private static String eventKey(int index) {
        return String.format("events/%04d.txt", index);
    }

    // Two log files written in the same flush cycle can land in the same delivery
    // minute, leaving only a random filename suffix (CloudTrailLogWriter#randomFilenameSuffix)
    // to distinguish their S3 keys. That suffix carries no relationship to write order, so
    // relying on S3 listing order across files (as real CloudTrail's own key layout would
    // also not support) is not a reliable way to reconstruct delivery order. Each event's
    // own key already encodes its original sequence, so sort on that instead.
    private static final Pattern EVENT_INDEX = Pattern.compile("(\\d+)\\.txt$");

    private static List<JsonNode> deliveredRecords(String bucket) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> records = new ArrayList<>();
        for (String key : listLogKeys(bucket)) {
            byte[] gz = given().when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            byte[] json;
            try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = gzin.readAllBytes();
            }
        mapper.readTree(json).path("Records").forEach(records::add);
        }
        records.sort(Comparator.comparingInt(CloudTrailLogWriterBoundedRetryIntegrationTest::sourceEventIndex));
        return records;
    }

    private static int sourceEventIndex(JsonNode record) {
        Matcher m = EVENT_INDEX.matcher(record.path("requestParameters").path("key").asText(""));
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static List<String> listLogKeys(String bucket) {
        String xml = given().when().get("/" + bucket + "?list-type=2")
                .then().statusCode(200).extract().asString();
        List<String> keys = new ArrayList<>();
        for (String key : XmlParser.extractAll(xml, "Key")) {
            if (key.contains("/CloudTrail/") && key.endsWith(".json.gz")) {
                keys.add(key);
            }
        }
        return keys;
    }
}
