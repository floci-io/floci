package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class CloudTrailLogWriterBatchingTest {

    @Inject
    CloudTrailLogWriter writer;

    @Inject
    CloudTrailService realService;

    @AfterEach
    void restoreRealService() {
        QuarkusMock.installMockForType(realService, CloudTrailService.class);
    }

    @Test
    void maximumSizedFlushWritesOneValidOrderedGzipObject() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "batch-max-logs-" + suffix;
        String trailName = "batch-max-trail-" + suffix;
        String region = "us-east-1";

        createBucket(bucket);

        CloudTrailService.TrailKey key = new CloudTrailService.TrailKey(region, trailName, region);
        Deque<ObjectNode> pending = records(CloudTrailLogWriter.MAX_RECORDS_PER_LOG_FILE);
        installMockCloudTrailService(bucket, trailName, region, key, pending);

        writer.flushNow();

        List<CloudTrailLogObject> objects = readCloudTrailObjects(bucket);
        assertEquals(1, objects.size());
        JsonNode envelope = objects.getFirst().envelope();
        assertEquals(1, envelope.size(), "Expected only the Records envelope field");
        assertTrue(envelope.path("Records").isArray(), "Records is not an array");
        assertRecordRange(toList(envelope.path("Records")), 0, 999);
    }

    @Test
    void flushWritesAllQueuedRecordsInBoundedObjects() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "batch-logs-" + suffix;
        String trailName = "batch-trail-" + suffix;
        String region = "us-east-1";

        createBucket(bucket);

        CloudTrailService.TrailKey key = new CloudTrailService.TrailKey(region, trailName, region);
        Deque<ObjectNode> pending = records(1_001);
        installMockCloudTrailService(bucket, trailName, region, key, pending);

        writer.flushNow();

        List<List<JsonNode>> allObjects = readCloudTrailRecordGroups(bucket);
        assertEquals(2, allObjects.size());
        allObjects = new ArrayList<>(allObjects);
        allObjects.sort(Comparator.comparingInt(CloudTrailLogWriterBatchingTest::firstRecordIndex));
        assertRecordRange(allObjects.get(0), 0, 999);
        assertRecordRange(allObjects.get(1), 1_000, 1_000);
    }

    @Test
    void sustainedBacklogDrainsIncrementallyInOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "batch-backlog-logs-" + suffix;
        String trailName = "batch-backlog-trail-" + suffix;
        String region = "us-east-1";

        createBucket(bucket);

        CloudTrailService.TrailKey key = new CloudTrailService.TrailKey(region, trailName, region);
        Deque<ObjectNode> pending = records(2_500);
        installMockCloudTrailService(bucket, trailName, region, key, pending);

        writer.flushNow();

        List<List<JsonNode>> recordGroups = readCloudTrailRecordGroups(bucket).stream()
                .sorted(Comparator.comparingInt(CloudTrailLogWriterBatchingTest::firstRecordIndex))
                .toList();

        assertEquals(3, recordGroups.size());
        assertRecordRange(recordGroups.get(0), 0, 999);
        assertRecordRange(recordGroups.get(1), 1_000, 1_999);
        assertRecordRange(recordGroups.get(2), 2_000, 2_499);
    }

    @Test
    void failedBoundedBatchRetriesBeforeNewerQueuedRecords() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBucket = "batch-source-" + suffix;
        String destinationBucket = "batch-retry-logs-" + suffix;
        String trailName = "batch-retry-trail-" + suffix;
        String region = "us-east-1";

        realService.createTrail(region, trailName, destinationBucket, null,
                null, true, false, false, false);
        realService.putEventSelectors(region, trailName, List.of(new EventSelector(
                "All",
                false,
                List.of(new DataResource("AWS::S3::Object",
                        List.of("arn:aws:s3:::" + sourceBucket + "/"))),
                List.of())));
        realService.startLogging(region, trailName);
        emitS3Events(sourceBucket, region, 0, 1_000);

        writer.flushNow();

        createBucket(destinationBucket);
        emitS3Events(sourceBucket, region, 1_001, 1_005);

        writer.flushNow();

        List<List<JsonNode>> recordGroups = new ArrayList<>(readCloudTrailRecordGroups(destinationBucket));
        recordGroups.sort(Comparator.comparingInt(CloudTrailLogWriterBatchingTest::firstRecordIndex));
        assertEquals(2, recordGroups.size());
        assertRecordRange(recordGroups.get(0), 0, 999);
        assertRecordRange(recordGroups.get(1), 1_000, 1_005);
    }

    @Test
    void overlappingFlushesForOneTrailAreSerialized() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "serialized-logs-" + suffix;
        String trailName = "serialized-trail-" + suffix;
        String region = "us-east-1";

        createBucket(bucket);

        CloudTrailService.TrailKey key = new CloudTrailService.TrailKey(region, trailName, region);
        Deque<ObjectNode> pending = records(2);
        Trail trail = new Trail(trailName,
                "arn:aws:cloudtrail:" + region + ":000000000000:trail/" + trailName,
                bucket, null, null, true, false, region, false, false, false, false);
        CountDownLatch firstDrainEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDrain = new CountDownLatch(1);
        CountDownLatch secondDrainEntered = new CountDownLatch(1);
        AtomicBoolean inDrain = new AtomicBoolean();
        AtomicBoolean overlap = new AtomicBoolean();
        AtomicInteger drainCalls = new AtomicInteger();

        CloudTrailService mockService = mock(CloudTrailService.class);
        when(mockService.trailsWithPendingRecords())
                .thenAnswer(inv -> pending.isEmpty() ? List.of() : List.of(key));
        when(mockService.pendingRecordCount(eq(key))).thenAnswer(inv -> pending.size());
        when(mockService.getTrail(region, trailName)).thenReturn(trail);
        when(mockService.drainPendingRecords(eq(key), eq(CloudTrailLogWriter.MAX_RECORDS_PER_LOG_FILE)))
                .thenAnswer(inv -> {
                    if (!inDrain.compareAndSet(false, true)) {
                        overlap.set(true);
                    }
                    try {
                        if (drainCalls.getAndIncrement() == 0) {
                            firstDrainEntered.countDown();
                            releaseFirstDrain.await(5, TimeUnit.SECONDS);
                        } else {
                            secondDrainEntered.countDown();
                        }
                        return drain(pending, CloudTrailLogWriter.MAX_RECORDS_PER_LOG_FILE);
                    } finally {
                        inDrain.set(false);
                    }
                });
        doAnswer(inv -> null).when(mockService).emitS3DataEvent(any());
        QuarkusMock.installMockForType(mockService, CloudTrailService.class);

        Thread first = new Thread(writer::flushNow);
        Thread second = new Thread(writer::flushNow);
        first.start();
        assertTrue(firstDrainEntered.await(5, TimeUnit.SECONDS));
        second.start();

        assertFalse(secondDrainEntered.await(200, TimeUnit.MILLISECONDS),
                "A second flush must wait for the first trail flush to finish");
        releaseFirstDrain.countDown();
        first.join(5_000);
        second.join(5_000);

        assertFalse(first.isAlive(), "First flush did not finish");
        assertFalse(second.isAlive(), "Second flush did not finish");
        assertFalse(overlap.get(), "Flushes for one trail overlapped");
    }

    private void installMockCloudTrailService(String bucket, String trailName, String region,
                                              CloudTrailService.TrailKey key,
                                              Deque<ObjectNode> pending) {
        Trail trail = new Trail(trailName,
                "arn:aws:cloudtrail:" + region + ":000000000000:trail/" + trailName,
                bucket, null, null, true, false, region, false, false, false, false);

        CloudTrailService mockService = mock(CloudTrailService.class);
        when(mockService.trailsWithPendingRecords())
                .thenAnswer(inv -> pending.isEmpty() ? List.of() : List.of(key));
        when(mockService.pendingRecordCount(eq(key))).thenAnswer(inv -> pending.size());
        when(mockService.getTrail(region, trailName)).thenReturn(trail);
        when(mockService.drainPendingRecords(eq(key), eq(CloudTrailLogWriter.MAX_RECORDS_PER_LOG_FILE)))
                .thenAnswer(inv -> drain(pending, CloudTrailLogWriter.MAX_RECORDS_PER_LOG_FILE));
        doAnswer(inv -> null).when(mockService).emitS3DataEvent(any());

        QuarkusMock.installMockForType(mockService, CloudTrailService.class);
    }

    private void emitS3Events(String bucket, String region, int first, int last) {
        for (int i = first; i <= last; i++) {
            realService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(region)
                    .eventName("PutObject")
                    .bucketName(bucket)
                    .key(objectKey(i))
                    .accessKeyId(null)
                    .sourceIp(null)
                    .userAgent("test")
                    .bytesIn(0)
                    .bytesOut(0)
                    .errorCode(null)
                    .errorMessage(null)
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
        }
    }

    private static List<ObjectNode> drain(Deque<ObjectNode> pending, int maxRecords) {
        List<ObjectNode> drained = new ArrayList<>();
        while (drained.size() < maxRecords && !pending.isEmpty()) {
            drained.add(pending.removeFirst());
        }
        return drained;
    }

    private static Deque<ObjectNode> records(int count) {
        ObjectMapper mapper = new ObjectMapper();
        Deque<ObjectNode> records = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            ObjectNode record = mapper.createObjectNode();
            record.put("eventName", "PutObject");
            record.putObject("requestParameters").put("key", objectKey(i));
            records.add(record);
        }
        return records;
    }

    private static void assertRecordRange(List<JsonNode> records, int first, int last) {
        assertEquals(last - first + 1, records.size());
        for (int i = first; i <= last; i++) {
            JsonNode record = records.get(i - first);
            assertEquals(objectKey(i), record.path("requestParameters").path("key").asText());
        }
    }

    private static String objectKey(int index) {
        return "object-" + String.format("%04d", index);
    }

    private static int firstRecordIndex(List<JsonNode> records) {
        return recordIndex(records.getFirst());
    }

    private static int recordIndex(JsonNode record) {
        String key = record.path("requestParameters").path("key").asText();
        return Integer.parseInt(key.substring("object-".length()));
    }

    private static void createBucket(String name) {
        given().when().put("/" + name).then().statusCode(200);
    }

    private static List<List<JsonNode>> readCloudTrailRecordGroups(String bucket) throws Exception {
        return readCloudTrailObjects(bucket).stream()
                .map(logObject -> toList(logObject.envelope().path("Records")))
                .toList();
    }

    private static List<CloudTrailLogObject> readCloudTrailObjects(String bucket) throws Exception {
        List<CloudTrailLogObject> objects = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String key : listCloudTrailKeys(bucket)) {
            byte[] gz = given().when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                JsonNode envelope = mapper.readTree(gzin);
                JsonNode records = envelope.get("Records");
                assertNotNull(records, "Missing Records[] in " + key);
                assertTrue(records.isArray(), "Records is not an array in " + key);
                objects.add(new CloudTrailLogObject(key, envelope));
            }
        }
        return objects;
    }

    private static List<JsonNode> toList(JsonNode records) {
        List<JsonNode> result = new ArrayList<>();
        records.forEach(result::add);
        return result;
    }

    private static List<String> listCloudTrailKeys(String bucket) {
        String xml = given().when().get("/" + bucket + "?list-type=2")
                .then().statusCode(200).extract().asString();
        List<String> keys = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = xml.indexOf("<Key>", from);
            if (open < 0) {
                break;
            }
            int close = xml.indexOf("</Key>", open);
            String key = xml.substring(open + 5, close);
            if (key.contains("/CloudTrail/") && key.endsWith(".json.gz")) {
                keys.add(key);
            }
            from = close + 6;
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private record CloudTrailLogObject(String key, JsonNode envelope) {
    }
}
