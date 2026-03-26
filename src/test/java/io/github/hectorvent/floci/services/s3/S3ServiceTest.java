package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class S3ServiceTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        Path dataRoot = tempDir.resolve("s3");
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot);
    }

    @Test
    void createBucket() {
        Bucket bucket = s3Service.createBucket("test-bucket", "us-east-1");
        assertEquals("test-bucket", bucket.getName());
        assertNotNull(bucket.getCreationDate());
    }

    @Test
    void createBucketStoresRegion() {
        s3Service.createBucket("eu-bucket", "eu-central-1");
        assertEquals("eu-central-1", s3Service.getBucketRegion("eu-bucket"));
    }

    @Test
    void createBucketNullRegionWhenNotProvided() {
        s3Service.createBucket("default-bucket", null);
        assertNull(s3Service.getBucketRegion("default-bucket"));
    }

    @Test
    void createDuplicateBucketThrows() {
        s3Service.createBucket("test-bucket", "us-east-1");
        assertThrows(AwsException.class, () -> s3Service.createBucket("test-bucket", "us-east-1"));
    }

    @Test
    void deleteBucket() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.deleteBucket("test-bucket");
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("test-bucket"));
    }

    @Test
    void deleteNonEmptyBucketThrows() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "hello".getBytes(), "text/plain", null);
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("test-bucket"));
    }

    @Test
    void deleteNonExistentBucketThrows() {
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("nonexistent"));
    }

    @Test
    void listBuckets() {
        s3Service.createBucket("bucket-a", "us-east-1");
        s3Service.createBucket("bucket-b", "us-east-1");

        List<Bucket> buckets = s3Service.listBuckets();
        assertEquals(2, buckets.size());
    }

    @Test
    void putObjectLastModifiedHasSecondPrecision() {
        s3Service.createBucket("test-bucket", null);
        S3Object obj = s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        assertEquals(0, obj.getLastModified().getNano());
    }

    @Test
    void putAndGetObject() {
        s3Service.createBucket("test-bucket", "us-east-1");
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        S3Object put = s3Service.putObject("test-bucket", "greeting.txt", data, "text/plain", null);

        assertNotNull(put.getETag());
        assertEquals(11, put.getSize());

        S3Object got = s3Service.getObject("test-bucket", "greeting.txt");
        assertArrayEquals(data, got.getData());
        assertEquals("text/plain", got.getContentType());
    }

    @Test
    void putObjectWritesFileToDisk() {
        s3Service.createBucket("test-bucket", "us-east-1");
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("test-bucket", "docs/readme.txt", data, "text/plain", null);

        Path filePath = tempDir.resolve("s3/test-bucket/docs/readme.txt");
        assertTrue(Files.exists(filePath));
        assertArrayEquals(data, assertDoesNotThrow(() -> Files.readAllBytes(filePath)));
    }

    @Test
    void deleteObjectRemovesFileFromDisk() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);

        Path filePath = tempDir.resolve("s3/test-bucket/file.txt");
        assertTrue(Files.exists(filePath));

        s3Service.deleteObject("test-bucket", "file.txt");
        assertFalse(Files.exists(filePath));
    }

    @Test
    void deleteBucketRemovesDirectory() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        s3Service.deleteObject("test-bucket", "file.txt");
        s3Service.deleteBucket("test-bucket");

        assertFalse(Files.exists(tempDir.resolve("s3/test-bucket")));
    }

    @Test
    void getObjectNotFoundThrows() {
        s3Service.createBucket("test-bucket", "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                s3Service.getObject("test-bucket", "missing.txt"));
        assertEquals("NoSuchKey", ex.getErrorCode());
    }

    @Test
    void putObjectToNonExistentBucketThrows() {
        assertThrows(AwsException.class, () ->
                s3Service.putObject("nonexistent", "file.txt", "data".getBytes(), null, null));
    }

    @Test
    void deleteObject() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        s3Service.deleteObject("test-bucket", "file.txt");

        assertThrows(AwsException.class, () ->
                s3Service.getObject("test-bucket", "file.txt"));
    }

    @Test
    void listObjects() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "docs/a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "docs/b.txt", "b".getBytes(), null, null);
        s3Service.putObject("test-bucket", "images/pic.jpg", "img".getBytes(), null, null);

        List<S3Object> all = s3Service.listObjects("test-bucket", null, null, 1000);
        assertEquals(3, all.size());

        List<S3Object> docs = s3Service.listObjects("test-bucket", "docs/", null, 1000);
        assertEquals(2, docs.size());
    }

    @Test
    void listObjectsInNonExistentBucketThrows() {
        assertThrows(AwsException.class, () ->
                s3Service.listObjects("nonexistent", null, null, 100));
    }

    @Test
    void copyObject() {
        s3Service.createBucket("source-bucket", "us-east-1");
        s3Service.createBucket("dest-bucket", "us-east-1");
        s3Service.putObject("source-bucket", "original.txt", "content".getBytes(), "text/plain", null);

        S3Object copy = s3Service.copyObject("source-bucket", "original.txt", "dest-bucket", "copy.txt");
        assertNotNull(copy.getETag());

        S3Object retrieved = s3Service.getObject("dest-bucket", "copy.txt");
        assertArrayEquals("content".getBytes(), retrieved.getData());

        // Verify file exists on disk for the copy
        assertTrue(Files.exists(tempDir.resolve("s3/dest-bucket/copy.txt")));
    }

    @Test
    void copyObjectSameBucket() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "original.txt", "data".getBytes(), null, null);
        s3Service.copyObject("test-bucket", "original.txt", "test-bucket", "copy.txt");

        assertNotNull(s3Service.getObject("test-bucket", "copy.txt"));
    }

    @Test
    void headObject() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "hello".getBytes(), "text/plain", null);

        S3Object head = s3Service.headObject("test-bucket", "file.txt");
        assertEquals(5, head.getSize());
        assertEquals("text/plain", head.getContentType());
        assertNull(head.getData());
    }

    @Test
    void putObjectOverwrites() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "v1".getBytes(), null, null);
        s3Service.putObject("test-bucket", "file.txt", "v2".getBytes(), null, null);

        S3Object obj = s3Service.getObject("test-bucket", "file.txt");
        assertArrayEquals("v2".getBytes(), obj.getData());
    }

    @Test
    void putObjectPersistsMetadataStorageClassAndChecksum() {
        s3Service.createBucket("test-bucket", "us-east-1");

        S3Object stored = s3Service.putObject("test-bucket", "docs/file.txt", "payload".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of("owner", "team-a"), "STANDARD_IA", null, null, null);

        S3Object head = s3Service.headObject("test-bucket", "docs/file.txt");
        assertEquals("STANDARD_IA", head.getStorageClass());
        assertEquals("team-a", head.getMetadata().get("owner"));
        assertNotNull(head.getChecksum());
        assertNotNull(head.getChecksum().getChecksumSHA256());
        assertEquals("FULL_OBJECT", head.getChecksum().getChecksumType());
        assertEquals(stored.getETag(), head.getETag());
    }

    @Test
    void getObjectAttributesReturnsRequestedFields() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "report.txt", "payload".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of("env", "dev"), "GLACIER", null, null, null);

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "report.txt", null,
                Set.of(ObjectAttributeName.E_TAG, ObjectAttributeName.OBJECT_SIZE,
                        ObjectAttributeName.STORAGE_CLASS, ObjectAttributeName.CHECKSUM),
                null, null);

        assertNotNull(attributes.getETag());
        assertEquals(7L, attributes.getObjectSize());
        assertEquals("GLACIER", attributes.getStorageClass());
        assertNotNull(attributes.getChecksum());
        assertNotNull(attributes.getChecksum().getChecksumSHA256());
        assertNull(attributes.getObjectParts());
    }

    @Test
    void copyObjectCanReplaceMetadata() {
        s3Service.createBucket("source-bucket", "us-east-1");
        s3Service.createBucket("dest-bucket", "us-east-1");
        s3Service.putObject("source-bucket", "original.txt", "content".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of("owner", "source"), "STANDARD", null, null, null);

        S3Object copy = s3Service.copyObject("source-bucket", "original.txt", "dest-bucket", "copy.txt",
                "REPLACE", Map.of("owner", "dest"), "STANDARD_IA", "application/json");

        assertEquals("application/json", copy.getContentType());
        assertEquals("STANDARD_IA", copy.getStorageClass());
        assertEquals("dest", copy.getMetadata().get("owner"));
    }

    // ──────────────────────────── S3 → SQS Cross-Region Notification ────────────────────────────

    @Test
    void s3NotificationDeliveresToCrossRegionSqsQueue() {
        String defaultRegion = "us-east-1";
        String crossRegion = "eu-west-1";
        String queueName = "s3-notification-queue";
        String sqsArn = "arn:aws:sqs:" + crossRegion + ":000000000000:" + queueName;

        RegionResolver regionResolver = new RegionResolver(defaultRegion, "000000000000");
        SqsService sqsService = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, "http://localhost:4566", regionResolver);

        sqsService.createQueue(queueName, null, crossRegion);

        Path dataRoot = tempDir.resolve("s3-cross-region");
        S3Service s3WithSqs = new S3Service(
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                dataRoot, sqsService, regionResolver);

        s3WithSqs.createBucket("notify-bucket", defaultRegion);

        NotificationConfiguration notifConfig = new NotificationConfiguration();
        notifConfig.setQueueConfigurations(List.of(
                new QueueNotification("notif-1", sqsArn, List.of("s3:ObjectCreated:*"))
        ));
        s3WithSqs.putBucketNotificationConfiguration("notify-bucket", notifConfig);

        s3WithSqs.putObject("notify-bucket", "test-key.txt", "hello".getBytes(StandardCharsets.UTF_8),
                "text/plain", null, "STANDARD", null, null, null);

        String queueUrl = "http://localhost:4566/000000000000/" + queueName;
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, crossRegion);
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().getBody().contains("test-key.txt"));
    }

    @Test
    void s3NotificationDeliveresToSameRegionSqsQueue() {
        String region = "us-east-1";
        String queueName = "s3-same-region-queue";
        String sqsArn = "arn:aws:sqs:" + region + ":000000000000:" + queueName;

        RegionResolver regionResolver = new RegionResolver(region, "000000000000");
        SqsService sqsService = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, "http://localhost:4566", regionResolver);

        sqsService.createQueue(queueName, null, region);

        Path dataRoot = tempDir.resolve("s3-same-region");
        S3Service s3WithSqs = new S3Service(
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                dataRoot, sqsService, regionResolver);

        s3WithSqs.createBucket("same-region-bucket", region);

        NotificationConfiguration notifConfig = new NotificationConfiguration();
        notifConfig.setQueueConfigurations(List.of(
                new QueueNotification("notif-1", sqsArn, List.of("s3:ObjectCreated:*"))
        ));
        s3WithSqs.putBucketNotificationConfiguration("same-region-bucket", notifConfig);

        s3WithSqs.putObject("same-region-bucket", "file.txt", "data".getBytes(StandardCharsets.UTF_8),
                "text/plain", null, "STANDARD", null, null, null);

        String queueUrl = "http://localhost:4566/000000000000/" + queueName;
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, region);
        assertEquals(1, messages.size());
    }
}
