package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces the review finding on {@link CloudTrailLogWriter#flushTrail}: the
 * self-delivery {@code emitS3DataEvent} call was folded into the same
 * try/catch as the S3 write. If emission fails AFTER the write already
 * succeeded, the catch re-queues records that were already durably delivered,
 * causing the next flush to write a duplicate log file to S3.
 *
 * <p>{@link CloudTrailService} is fully mocked here (rather than spied) to
 * avoid recursing through its Arc client proxy, and to deterministically
 * force the post-write emission failure that is not otherwise reachable
 * through the real implementation (which defensively swallows its own
 * exceptions).
 */
@QuarkusTest
class CloudTrailLogWriterRequeueTest {

    @Inject
    CloudTrailLogWriter writer;

    @Inject
    CloudTrailService realService;

    @AfterEach
    void restoreRealService() {
        QuarkusMock.installMockForType(realService, CloudTrailService.class);
    }

    @Test
    void selfDeliveryEmissionFailureAfterSuccessfulWriteDoesNotDuplicateDelivery() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "requeue-logs-" + suffix;
        String trailName = "requeue-trail-" + suffix;
        String region = "us-east-1";

        createBucket(bucket);

        Trail trail = new Trail(trailName, "arn:aws:cloudtrail:" + region + ":000000000000:trail/" + trailName,
                bucket, null, null, true, false, region, false, false, false, false);
        CloudTrailService.TrailKey key = new CloudTrailService.TrailKey(region, trailName, region);

        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> pending = new ArrayList<>(List.of(mapper.createObjectNode().put("eventName", "PutObject")));

        CloudTrailService mockService = mock(CloudTrailService.class);
        when(mockService.trailsWithPendingRecords())
                .thenAnswer(inv -> pending.isEmpty() ? List.of() : List.of(key));
        when(mockService.getTrail(region, trailName)).thenReturn(trail);
        when(mockService.drainPendingRecords(key)).thenAnswer(inv -> {
            List<ObjectNode> drained = new ArrayList<>(pending);
            pending.clear();
            return drained;
        });
        doAnswer(inv -> {
            List<ObjectNode> records = inv.getArgument(1);
            pending.addAll(records);
            return null;
        }).when(mockService).requeueRecords(eq(key), any());
        doThrow(new RuntimeException("boom: self-delivery emission failed"))
                .when(mockService).emitS3DataEvent(any());

        QuarkusMock.installMockForType(mockService, CloudTrailService.class);

        // Delivery attempt #1: the S3 write succeeds, but the self-delivery
        // emission afterward throws. Under the bug, that throw re-queues the
        // already-written records back into `pending`.
        writer.flushNow();

        // Delivery attempt #2: if the records were wrongly re-queued, this
        // flush writes them again, producing a second log file for the same
        // batch.
        writer.flushNow();

        List<String> logFiles = new ArrayList<>();
        for (String k : listKeys(bucket)) {
            if (k.contains("/CloudTrail/") && k.endsWith(".json.gz")) {
                logFiles.add(k);
            }
        }
        assertEquals(1, logFiles.size(),
                "Expected exactly one delivered log file: a self-delivery emission "
                        + "failure must not re-queue records that were already durably "
                        + "written to S3, but found: " + logFiles);
    }

    // --- Helpers ---

    private static void createBucket(String name) {
        given().when().put("/" + name).then().statusCode(200);
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
}
