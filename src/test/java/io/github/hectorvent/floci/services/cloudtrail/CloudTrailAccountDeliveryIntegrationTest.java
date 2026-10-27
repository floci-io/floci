package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudTrailAccountDeliveryIntegrationTest {
    private static final String REGION = "eu-west-1";
    private static final String FIRST_ACCOUNT = "661100000004";
    private static final String SECOND_ACCOUNT = "661100000005";

    @Inject
    CloudTrailService trails;

    @Inject
    CloudTrailLogWriter writer;

    @Inject
    S3Service s3;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalTrailNamesKeepAccountBuffersAndBackgroundDeliverySeparate(boolean deleteFirstTrail) throws Exception {
        String name = "account-events-" + UUID.randomUUID();
        Map<String, String> buckets = Map.of(FIRST_ACCOUNT, name + "-first", SECOND_ACCOUNT, name + "-second");
        try {
            buckets.forEach((account, bucket) -> createS3DataTrail(account, REGION, name, bucket));
            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.startLogging(REGION, name));
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> {
                trails.emitS3DataEvent(event());
                assertEquals(0, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)),
                        "another account's trail cannot enable events");
                trails.startLogging(REGION, name);
            });
            for (String account : buckets.keySet()) {
                RequestScopes.runAs(account, REGION, () -> {
                    trails.emitS3DataEvent(event());
                    assertEquals(1, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)));
                });
            }
            if (deleteFirstTrail) {
                RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.deleteTrail(REGION, name));
            }
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> assertEquals(1,
                    trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)),
                    "deleting one account's trail must preserve the other account's pending records"));

            flushLogsOnWorker();

            for (String account : buckets.keySet()) {
                if (deleteFirstTrail && FIRST_ACCOUNT.equals(account)) {
                    RequestScopes.runAs(account, REGION, () -> assertTrue(
                            s3.listObjects(buckets.get(account), null, null, 100).isEmpty()));
                    continue;
                }
                JsonNode records = readDeliveredRecords(account, REGION, buckets.get(account));
                assertEquals(1, records.size());
                assertEquals("Data", records.get(0).path("eventCategory").asText());
                assertEquals(account, records.get(0).path("recipientAccountId").asText());
                assertEquals(account, records.get(0).path("userIdentity").path("accountId").asText());
                RequestScopes.runAs(account, REGION, () -> assertEquals(0,
                        trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION))));
            }
        } finally {
            buckets.forEach((account, bucket) -> deleteTrailAndBucket(account, REGION, name, bucket));
        }
    }

    @Test
    void deletingTrailByArnPreservesSameNamedTrailInAnotherHomeRegion() throws Exception {
        String retainedRegion = "us-west-2";
        String name = "region-events-" + UUID.randomUUID();
        Map<String, String> buckets = Map.of(REGION, name + "-deleted", retainedRegion, name + "-retained");
        try {
            buckets.forEach((region, bucket) -> {
                createS3DataTrail(FIRST_ACCOUNT, region, name, bucket);
                RequestScopes.runAs(FIRST_ACCOUNT, region, () -> {
                    trails.startLogging(region, name);
                    trails.emitS3DataEvent(event(region));
                    assertEquals(1, trails.pendingRecordCount(new CloudTrailService.TrailKey(region, name, region)));
                });
            });
            String deletedArn = RequestScopes.callAs(FIRST_ACCOUNT, REGION,
                    () -> trails.getTrail(REGION, name).trailArn());
            RequestScopes.runAs(FIRST_ACCOUNT, retainedRegion, () -> {
                trails.deleteTrail(retainedRegion, deletedArn);
                assertEquals(0, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)));
                assertEquals(1, trails.pendingRecordCount(new CloudTrailService.TrailKey(retainedRegion, name, retainedRegion)),
                        "deleting by ARN must preserve the same account's other home-region buffer");
            });

            flushLogsOnWorker();

            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> assertTrue(
                    s3.listObjects(buckets.get(REGION), null, null, 100).isEmpty()));
            JsonNode records = readDeliveredRecords(FIRST_ACCOUNT, retainedRegion, buckets.get(retainedRegion));
            assertEquals(1, records.size());
            assertEquals(FIRST_ACCOUNT, records.get(0).path("recipientAccountId").asText());
            assertEquals(retainedRegion, records.get(0).path("awsRegion").asText());
            assertEquals("PutObject", records.get(0).path("eventName").asText());
            RequestScopes.runAs(FIRST_ACCOUNT, retainedRegion, () -> assertEquals(0,
                    trails.pendingRecordCount(new CloudTrailService.TrailKey(retainedRegion, name, retainedRegion))));
        } finally {
            buckets.forEach((region, bucket) -> deleteTrailAndBucket(FIRST_ACCOUNT, region, name, bucket));
        }
    }

    private void createS3DataTrail(String account, String region, String name, String bucket) {
        RequestScopes.runAs(account, region, () -> {
            s3.createBucket(bucket, region);
            trails.createTrail(region, name, bucket, null, null, false, false, false, false);
            trails.putEventSelectors(region, name, List.of(new EventSelector("All", false,
                    List.of(new DataResource("AWS::S3::Object", List.of("arn:aws:s3:::source/"))), List.of())));
        });
    }

    private void flushLogsOnWorker() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            worker.submit(writer::flushNow).get(10, TimeUnit.SECONDS);
        }
    }

    private JsonNode readDeliveredRecords(String account, String region, String bucket) throws Exception {
        List<S3Object> objects = RequestScopes.callAs(account, region,
                () -> s3.listObjects(bucket, null, null, 100));
        assertEquals(1, objects.size(), "each trail must receive its own log file");
        assertTrue(objects.getFirst().getKey().startsWith("AWSLogs/" + account + "/CloudTrail/" + region + "/"));
        byte[] content = RequestScopes.callAs(account, region,
                () -> s3.getObject(bucket, objects.getFirst().getKey()).getData());
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            return mapper.readTree(gzip).path("Records");
        }
    }

    private void deleteTrailAndBucket(String account, String region, String name, String bucket) {
        RequestScopes.runAs(account, region, () -> {
            if (trails.getTrail(region, name) != null) {
                trails.deleteTrail(region, name);
            }
            for (S3Object object : s3.listObjects(bucket, null, null, 100)) {
                s3.deleteObject(bucket, object.getKey());
            }
            s3.deleteBucket(bucket);
        });
    }

    private CloudTrailService.S3EventInput event() {
        return event(REGION);
    }

    private CloudTrailService.S3EventInput event(String region) {
        return CloudTrailService.S3EventInput.builder()
                .region(region).eventName("PutObject").bucketName("source").key("example.txt").build();
    }
}
