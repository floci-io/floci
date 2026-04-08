package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
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
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
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

        Path filePath = tempDir.resolve("s3/test-bucket/docs/readme.txt.s3data");
        assertTrue(Files.exists(filePath));
        assertArrayEquals(data, assertDoesNotThrow(() -> Files.readAllBytes(filePath)));
    }

    @Test
    void deleteObjectRemovesFileFromDisk() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);

        Path filePath = tempDir.resolve("s3/test-bucket/file.txt.s3data");
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
    void listObjectsWithDelimiterReturnsCommonPrefixes() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "docs/a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "docs/sub/deep.txt", "d".getBytes(), null, null);
        s3Service.putObject("test-bucket", "images/pic.jpg", "img".getBytes(), null, null);
        s3Service.putObject("test-bucket", "root.txt", "r".getBytes(), null, null);

        S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes("test-bucket", null, "/", 1000);
        List<String> rootKeys = result.objects().stream().map(S3Object::getKey).toList();
        assertEquals(List.of("root.txt"), rootKeys);
        assertEquals(List.of("docs/", "images/"), result.commonPrefixes());
        assertFalse(result.isTruncated());

        S3Service.ListObjectsResult docsResult = s3Service.listObjectsWithPrefixes("test-bucket", "docs/", "/", 1000);
        List<String> docKeys = docsResult.objects().stream().map(S3Object::getKey).toList();
        assertEquals(List.of("docs/a.txt"), docKeys);
        assertEquals(List.of("docs/sub/"), docsResult.commonPrefixes());
        assertFalse(docsResult.isTruncated());
    }

    @Test
    void listObjectsWithDelimiterRespectsMaxKeysAcrossObjectsAndPrefixes() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "b.txt", "b".getBytes(), null, null);
        s3Service.putObject("test-bucket", "dir1/file.txt", "f1".getBytes(), null, null);
        s3Service.putObject("test-bucket", "dir2/file.txt", "f2".getBytes(), null, null);
        s3Service.putObject("test-bucket", "dir3/file.txt", "f3".getBytes(), null, null);

        S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes("test-bucket", null, "/", 3);

        int totalReturned = result.objects().size() + result.commonPrefixes().size();
        assertEquals(3, totalReturned, "combined objects + commonPrefixes must not exceed maxKeys");
        assertTrue(result.isTruncated(), "result should be truncated when maxKeys < total entries");
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

        assertTrue(Files.exists(tempDir.resolve("s3/dest-bucket/copy.txt.s3data")));
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
    void putObjectKeyOverlappingWithPrefixDoesNotConflict() {
        s3Service.createBucket("test-bucket", "us-east-1");

        byte[] childData = "parquet-partition".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("test-bucket", "output.parquet/part-0001.parquet", childData, "application/octet-stream", null);

        byte[] markerData = new byte[0];
        assertDoesNotThrow(() ->
                s3Service.putObject("test-bucket", "output.parquet", markerData, "application/x-directory", null));

        S3Object child = s3Service.getObject("test-bucket", "output.parquet/part-0001.parquet");
        assertArrayEquals(childData, child.getData());

        S3Object marker = s3Service.getObject("test-bucket", "output.parquet");
        assertArrayEquals(markerData, marker.getData());

        Path bucketDir = tempDir.resolve("s3/test-bucket");
        assertTrue(Files.isDirectory(bucketDir.resolve("output.parquet")));
        assertTrue(Files.isRegularFile(bucketDir.resolve("output.parquet.s3data")));
        assertTrue(Files.isRegularFile(bucketDir.resolve("output.parquet/part-0001.parquet.s3data")));
    }

    @Test
    void putObjectMarkerFirstThenChildDoesNotConflict() {
        s3Service.createBucket("test-bucket", "us-east-1");

        byte[] markerData = new byte[0];
        s3Service.putObject("test-bucket", "output.parquet", markerData, "application/x-directory", null);

        byte[] childData = "parquet-partition".getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() ->
                s3Service.putObject("test-bucket", "output.parquet/part-0001.parquet", childData, "application/octet-stream", null));

        S3Object marker = s3Service.getObject("test-bucket", "output.parquet");
        assertArrayEquals(markerData, marker.getData());

        S3Object child = s3Service.getObject("test-bucket", "output.parquet/part-0001.parquet");
        assertArrayEquals(childData, child.getData());

        Path bucketDir = tempDir.resolve("s3/test-bucket");
        assertTrue(Files.isRegularFile(bucketDir.resolve("output.parquet.s3data")));
        assertTrue(Files.isDirectory(bucketDir.resolve("output.parquet")));
        assertTrue(Files.isRegularFile(bucketDir.resolve("output.parquet/part-0001.parquet.s3data")));
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

    @Test
    void copyObjectWithNonASCIIKey() {
        s3Service.createBucket("test-bucket", "us-east-1");
        String nonASCIIKey = "src/テスト画像.png";
        s3Service.putObject("test-bucket", nonASCIIKey, "image-data".getBytes(), "image/png", null);

        String destKey = "dst/テスト画像.png";
        S3Object copy = s3Service.copyObject("test-bucket", nonASCIIKey, "test-bucket", destKey);
        assertNotNull(copy.getETag());

        S3Object retrieved = s3Service.getObject("test-bucket", destKey);
        assertArrayEquals("image-data".getBytes(), retrieved.getData());
    }

    @Test
    void putObjectTriggersLambdaNotificationWhenKeyMatches() {
        RecordingLambdaInvoker lambdaInvoker = new RecordingLambdaInvoker();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("notif-s3"),
                false, lambdaInvoker, regionResolver);
        service.createBucket("test-bucket", "ap-northeast-1");
        service.putBucketNotificationConfiguration("test-bucket", lambdaNotificationConfig("uploads/", ".json"));

        service.putObject("test-bucket", "uploads/test.json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                "application/json", null);

        assertEquals("ap-northeast-1", lambdaInvoker.region);
        assertEquals("s3-notif-test", lambdaInvoker.functionName);
        assertEquals(InvocationType.Event, lambdaInvoker.type);
        assertNotNull(lambdaInvoker.payload);
    }

    @Test
    void putObjectDoesNotTriggerLambdaNotificationWhenKeyDoesNotMatch() {
        RecordingLambdaInvoker lambdaInvoker = new RecordingLambdaInvoker();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("notif-s3-no-match"),
                false, lambdaInvoker, regionResolver);
        service.createBucket("test-bucket", "ap-northeast-1");
        service.putBucketNotificationConfiguration("test-bucket", lambdaNotificationConfig("uploads/", ".json"));

        service.putObject("test-bucket", "incoming/test.txt", "ignored".getBytes(StandardCharsets.UTF_8),
                "text/plain", null);

        assertNull(lambdaInvoker.functionName);
    }

    private static NotificationConfiguration lambdaNotificationConfig(String prefix, String suffix) {
        NotificationConfiguration config = new NotificationConfiguration();
        config.getLambdaFunctionConfigurations().add(new LambdaNotification(
                "lambda-notif",
                "arn:aws:lambda:ap-northeast-1:000000000000:function:s3-notif-test",
                List.of("s3:ObjectCreated:Put"),
                List.of(
                        new FilterRule("prefix", prefix),
                        new FilterRule("suffix", suffix)
                )));
        return config;
    }

    private static final class RecordingLambdaInvoker implements S3Service.LambdaInvoker {
        private String region;
        private String functionName;
        private byte[] payload;
        private InvocationType type;

        @Override
        public void invoke(String region, String functionName, byte[] payload, InvocationType type) {
            this.region = region;
            this.functionName = functionName;
            this.payload = payload;
            this.type = type;
        }
    }
}
