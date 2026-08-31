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
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.WebsiteConfiguration;
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
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void constructorThrowsWhenDataRootUncreatable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores directory write permissions");
        Path readOnlyParent = tempDir.resolve("ro");
        Files.createDirectories(readOnlyParent);
        assertTrue(readOnlyParent.toFile().setWritable(false));
        try {
            Path dataRoot = readOnlyParent.resolve("s3");
            assertThrows(java.io.UncheckedIOException.class,
                    () -> new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false));
        } finally {
            assertTrue(readOnlyParent.toFile().setWritable(true));
        }
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
    void objectExistsReturnsFalseForMissingKey() {
        s3Service.createBucket("exists-bucket", "us-east-1");
        assertFalse(s3Service.objectExists("exists-bucket", "no-such-key"));
    }

    @Test
    void objectExistsRethrowsWhenBucketMissing() {
        // A missing key returns false, but a non-"not found" storage error (here NoSuchBucket) must
        // surface rather than be masked as "object absent".
        AwsException e = assertThrows(AwsException.class,
                () -> s3Service.objectExists("no-such-bucket", "any-key"));
        assertEquals("NoSuchBucket", e.getErrorCode());
    }

    @Test
    void createDuplicateBucketInUsEast1IsIdempotent() {
        Bucket first = s3Service.createBucket("test-bucket", "us-east-1");
        Bucket duplicate = s3Service.createBucket("test-bucket", "us-east-1");
        assertSame(first, duplicate);
    }

    @Test
    void createDuplicateBucketOutsideUsEast1Throws() {
        s3Service.createBucket("eu-bucket", "eu-central-1");
        assertThrows(AwsException.class, () -> s3Service.createBucket("eu-bucket", "eu-central-1"));
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
    void putObjectLastModifiedHasMillisecondPrecision() {
        s3Service.createBucket("test-bucket", null);
        S3Object obj = s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        assertEquals(0, obj.getLastModified().getNano() % 1_000_000);
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
    void putObjectTrimsBlankServerSideEncryptionToAbsent() {
        s3Service.createBucket("test-bucket", "us-east-1");

        S3Object put = s3Service.putObject(
                "test-bucket",
                "blank-sse.txt",
                "data".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                null,
                new PutObjectOptions().withServerSideEncryption("   ")
        );

        assertNull(put.getServerSideEncryption());
    }

    @Test
    void putObjectRejectsUnsupportedServerSideEncryption() {
        s3Service.createBucket("test-bucket", "us-east-1");

        AwsException exception = assertThrows(AwsException.class, () ->
                s3Service.putObject(
                        "test-bucket",
                        "invalid-sse.txt",
                        "data".getBytes(StandardCharsets.UTF_8),
                        "text/plain",
                        null,
                        new PutObjectOptions().withServerSideEncryption("totally-unsupported")
                )
        );

        assertEquals("InvalidArgument", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Unsupported x-amz-server-side-encryption value"));
    }

    @Test
    void putObjectWritesFileToDisk() {
        s3Service.createBucket("test-bucket", "us-east-1");
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("test-bucket", "docs/readme.txt", data, "text/plain", null);

        Path filePath = tempDir.resolve("s3/.accounts/000000000000/test-bucket/docs/readme.txt.s3data");
        assertTrue(Files.exists(filePath));
        assertArrayEquals(data, assertDoesNotThrow(() -> Files.readAllBytes(filePath)));
    }

    @Test
    void putObjectDoesNotRetainBytesInObjectStore() {
        InMemoryStorage<String, Bucket> bucketStore = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objectStore = new InMemoryStorage<>();
        Path dataRoot = tempDir.resolve("leak-test-s3");
        S3Service service = new S3Service(bucketStore, objectStore, dataRoot, false);

        service.createBucket("leak-bucket", "us-east-1");
        byte[] payload = new byte[64 * 1024];
        service.putObject("leak-bucket", "big.bin", payload, "application/octet-stream", null);

        S3Object cached = objectStore.get("leak-bucket/big.bin").orElseThrow();
        assertNull(cached.getData(),
                "S3Object cached in objectStore must not retain byte[] payload after disk persistence");
    }

    @Test
    void putObjectVersionedDoesNotRetainBytesInObjectStore() {
        InMemoryStorage<String, Bucket> bucketStore = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> objectStore = new InMemoryStorage<>();
        Path dataRoot = tempDir.resolve("leak-test-versioned-s3");
        S3Service service = new S3Service(bucketStore, objectStore, dataRoot, false);

        service.createBucket("versioned-leak-bucket", "us-east-1");
        service.putBucketVersioning("versioned-leak-bucket", "Enabled");
        byte[] payload = new byte[64 * 1024];
        S3Object put = service.putObject("versioned-leak-bucket", "big.bin", payload,
                "application/octet-stream", null);

        S3Object latest = objectStore.get("versioned-leak-bucket/big.bin").orElseThrow();
        S3Object versioned = objectStore.get("versioned-leak-bucket/big.bin#v#" + put.getVersionId())
                .orElseThrow();
        assertNull(latest.getData(),
                "Latest S3Object cached in objectStore must not retain byte[] after disk persistence");
        assertNull(versioned.getData(),
                "Versioned S3Object cached in objectStore must not retain byte[] after disk persistence");
    }

    @Test
    void deleteObjectRemovesFileFromDisk() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);

        Path filePath = tempDir.resolve("s3/.accounts/000000000000/test-bucket/file.txt.s3data");
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

        assertTrue(Files.exists(tempDir.resolve("s3/.accounts/000000000000/dest-bucket/copy.txt.s3data")));
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
        assertNotNull(head.getChecksum().getChecksumCRC64NVME());
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
        assertNotNull(attributes.getChecksum().getChecksumCRC64NVME());
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

        Path bucketDir = tempDir.resolve("s3/.accounts/000000000000/test-bucket");
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

        Path bucketDir = tempDir.resolve("s3/.accounts/000000000000/test-bucket");
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

    @Test
    void listObjectsWithStartAfterFiltersResults() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "b.txt", "b".getBytes(), null, null);
        s3Service.putObject("test-bucket", "c.txt", "c".getBytes(), null, null);

        S3Service.ListObjectsResult result = s3Service.listObjectsWithPrefixes(
                "test-bucket", null, null, 1000, null, "a.txt");
        List<String> keys = result.objects().stream().map(S3Object::getKey).toList();
        assertEquals(List.of("b.txt", "c.txt"), keys);
        assertFalse(result.isTruncated());
    }

    @Test
    void listObjectsWithContinuationTokenPaginates() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "b.txt", "b".getBytes(), null, null);
        s3Service.putObject("test-bucket", "c.txt", "c".getBytes(), null, null);

        // First page
        S3Service.ListObjectsResult page1 = s3Service.listObjectsWithPrefixes(
                "test-bucket", null, null, 2, null, null);
        assertEquals(2, page1.objects().size());
        assertTrue(page1.isTruncated());
        assertNotNull(page1.nextContinuationToken());

        // Second page using the token
        S3Service.ListObjectsResult page2 = s3Service.listObjectsWithPrefixes(
                "test-bucket", null, null, 2, page1.nextContinuationToken(), null);
        assertEquals(1, page2.objects().size());
        assertFalse(page2.isTruncated());
        assertEquals("c.txt", page2.objects().get(0).getKey());
    }

    @Test
    void resolvePathWithTraversalThrows() {
        s3Service.createBucket("test-bucket", "us-east-1");
        
        // Blocked: going above the bucket root
        AwsException ex = assertThrows(AwsException.class, () -> 
                s3Service.putObject("test-bucket", "../outside.txt", "data".getBytes(), null, null));
        assertEquals("InvalidKey", ex.getErrorCode());
        
        // Blocked: deeper traversal
        assertThrows(AwsException.class, () -> 
                s3Service.getObject("test-bucket", "dir/../../../etc/passwd"));
    }

    @Test
    void putObjectWithInternalTraversalStaysWithinBucket() {
        s3Service.createBucket("test-bucket", "us-east-1");
        byte[] data = "safe-content".getBytes();

        // Allowed: traversal that normalizes to a path still inside the bucket
        assertDoesNotThrow(() ->
                s3Service.putObject("test-bucket", "docs/../file.txt", data, null, null));

        // Retrieve using the same literal key (S3 keys are opaque strings)
        S3Object got = s3Service.getObject("test-bucket", "docs/../file.txt");
        assertArrayEquals(data, got.getData());
    }

    // =========================================================================
    // Website request resolution
    //
    // The HTTP rendering of these outcomes is covered end-to-end by
    // S3WebsiteIntegrationTest; these pin the policy itself, with no HTTP layer.
    // =========================================================================

    private static final S3Service.RequestAuthorization UNSIGNED =
            new S3Service.RequestAuthorization(false, null);

    private void websiteBucket(String index, String errorDoc) {
        s3Service.createBucket("site", "us-east-1");
        s3Service.putBucketWebsite("site", new WebsiteConfiguration(index, errorDoc));
    }

    @Test
    void resolveWebsiteRequestServesIndexForDirectoryRequest() {
        websiteBucket("index.html", null);
        s3Service.putObject("site", "docs/index.html", "hi".getBytes(), "text/html", null);

        var resolution = s3Service.resolveWebsiteRequest("site", "docs", true, UNSIGNED);

        var serve = assertInstanceOf(S3Service.WebsiteResolution.ServeObject.class, resolution);
        assertEquals("docs/index.html", serve.key());
    }

    @Test
    void resolveWebsiteRequestServesRootIndexWhenKeyIsEmpty() {
        websiteBucket("index.html", null);
        s3Service.putObject("site", "index.html", "root".getBytes(), "text/html", null);

        // The site root is a directory request even though the caller saw no trailing slash.
        var resolution = s3Service.resolveWebsiteRequest("site", "", false, UNSIGNED);

        assertEquals("index.html",
                assertInstanceOf(S3Service.WebsiteResolution.ServeObject.class, resolution).key());
    }

    @Test
    void resolveWebsiteRequestRedirectsFolderWithoutTrailingSlash() {
        websiteBucket("index.html", null);
        s3Service.putObject("site", "docs/index.html", "hi".getBytes(), "text/html", null);

        // No trailing slash, no object at "docs", but an index lives beneath it.
        var resolution = s3Service.resolveWebsiteRequest("site", "docs", false, UNSIGNED);

        assertInstanceOf(S3Service.WebsiteResolution.RedirectToDirectory.class, resolution);
    }

    @Test
    void resolveWebsiteRequestPrefersExactObjectOverFolderRedirect() {
        websiteBucket("index.html", null);
        s3Service.putObject("site", "docs", "exact".getBytes(), "text/plain", null);
        s3Service.putObject("site", "docs/index.html", "hi".getBytes(), "text/html", null);

        // An exact object hit is served by the normal object path, not redirected.
        var resolution = s3Service.resolveWebsiteRequest("site", "docs", false, UNSIGNED);

        assertInstanceOf(S3Service.WebsiteResolution.NotAWebsite.class, resolution);
    }

    @Test
    void resolveWebsiteRequestFallsBackToErrorDocumentWhenIndexMissing() {
        websiteBucket("index.html", "error.html");
        s3Service.putObject("site", "error.html", "oops".getBytes(), "text/html", null);

        var resolution = s3Service.resolveWebsiteRequest("site", "missing", true, UNSIGNED);

        var err = assertInstanceOf(S3Service.WebsiteResolution.ErrorDocument.class, resolution);
        assertEquals(404, err.status());
        assertArrayEquals("oops".getBytes(), err.object().getData());
    }

    @Test
    void resolveWebsiteRequestFallsBackToDefaultErrorWhenNoErrorDocumentConfigured() {
        websiteBucket("index.html", null);

        var resolution = s3Service.resolveWebsiteRequest("site", "missing", true, UNSIGNED);

        assertEquals(404,
                assertInstanceOf(S3Service.WebsiteResolution.DefaultError.class, resolution).status());
    }

    @Test
    void resolveWebsiteRequestFallsBackToDefaultErrorWhenErrorDocumentItselfMissing() {
        // Configured but never uploaded: S3 serves its built-in page rather than 500-ing.
        websiteBucket("index.html", "error.html");

        var resolution = s3Service.resolveWebsiteRequest("site", "missing", true, UNSIGNED);

        assertEquals(404,
                assertInstanceOf(S3Service.WebsiteResolution.DefaultError.class, resolution).status());
    }

    @Test
    void resolveWebsiteRequestIsNotAWebsiteWithoutConfiguration() {
        s3Service.createBucket("plain", "us-east-1");

        var resolution = s3Service.resolveWebsiteRequest("plain", "any", true, UNSIGNED);

        assertInstanceOf(S3Service.WebsiteResolution.NotAWebsite.class, resolution);
    }

    @Test
    void resolveWebsiteRequestPropagatesRealBucketErrors() {
        // A missing bucket must surface as NoSuchBucket, not be masked as "not a website".
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.resolveWebsiteRequest("no-such-bucket", "any", true, UNSIGNED));
        assertEquals("NoSuchBucket", error.getErrorCode());
    }

    @Test
    void resolveWebsiteErrorReturnsNotAWebsiteWithoutConfiguration() {
        s3Service.createBucket("plain", "us-east-1");

        assertInstanceOf(S3Service.WebsiteResolution.NotAWebsite.class,
                s3Service.resolveWebsiteError("plain", UNSIGNED, 404));
    }

    @Test
    void resolveWebsiteErrorMapsForbiddenToItsOwnStatus() {
        websiteBucket("index.html", "error.html");
        s3Service.putObject("site", "error.html", "denied".getBytes(), "text/html", null);

        // 403 keeps its status; everything else collapses to 404, matching S3.
        assertEquals(403,
                assertInstanceOf(S3Service.WebsiteResolution.ErrorDocument.class,
                        s3Service.resolveWebsiteError("site", UNSIGNED, 403)).status());
        assertEquals(404,
                assertInstanceOf(S3Service.WebsiteResolution.ErrorDocument.class,
                        s3Service.resolveWebsiteError("site", UNSIGNED, 500)).status());
    }

    @Test
    void metricsConfigurationsOnABucketWithoutAnyBehaveAsEmpty() {
        // A bucket persisted before this field existed deserializes with a null map, which is the
        // same shape a freshly created bucket has, so neither may fault.
        s3Service.createBucket("no-metrics", "us-east-1");

        assertTrue(s3Service.listBucketMetricsConfigurations("no-metrics")
                .contains("<IsTruncated>false</IsTruncated>"));
        assertEquals("NoSuchConfiguration", assertThrows(AwsException.class,
                () -> s3Service.getBucketMetricsConfiguration("no-metrics", "any")).getErrorCode());
        assertEquals("NoSuchConfiguration", assertThrows(AwsException.class,
                () -> s3Service.deleteBucketMetricsConfiguration("no-metrics", "any")).getErrorCode());

        // And the first put still lands on it.
        s3Service.putBucketMetricsConfiguration("no-metrics", "first", "<Id>first</Id>");
        assertTrue(s3Service.getBucketMetricsConfiguration("no-metrics", "first")
                .contains("<Id>first</Id>"));
    }

    @Test
    void metricsConfigurationsDoNotOutliveTheirBucket() {
        s3Service.createBucket("recycled", "us-east-1");
        s3Service.putBucketMetricsConfiguration("recycled", "old", "<Id>old</Id>");
        s3Service.deleteBucket("recycled");

        s3Service.createBucket("recycled", "us-east-1");

        assertEquals("NoSuchConfiguration", assertThrows(AwsException.class,
                () -> s3Service.getBucketMetricsConfiguration("recycled", "old")).getErrorCode());
    }

    @Test
    void metricsConfigurationsSurviveARestart() {
        // Through the real storage layer rather than Jackson alone: written, flushed to disk, and
        // read back by a second service over the same file, the way a restart does it.
        Path bucketsFile = tempDir.resolve("s3-buckets.json");
        var beforeRestart = new io.github.hectorvent.floci.core.storage.HybridStorage<String, Bucket>(
                bucketsFile, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Bucket>>() {}, 60000);
        S3Service before = new S3Service(beforeRestart, new InMemoryStorage<>(), tempDir.resolve("s3a"), false);
        before.createBucket("persisted-metrics", "us-east-1");
        before.putBucketMetricsConfiguration("persisted-metrics", "EntireBucket", "<Id>EntireBucket</Id>");
        beforeRestart.flush();
        beforeRestart.shutdown();

        var afterRestart = new io.github.hectorvent.floci.core.storage.HybridStorage<String, Bucket>(
                bucketsFile, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Bucket>>() {}, 60000);
        afterRestart.load();
        try {
            S3Service after = new S3Service(afterRestart, new InMemoryStorage<>(), tempDir.resolve("s3a"), false);
            assertTrue(after.getBucketMetricsConfiguration("persisted-metrics", "EntireBucket")
                    .contains("<Id>EntireBucket</Id>"));
        } finally {
            afterRestart.shutdown();
        }
    }

    @Test
    void metricsConfigurationsSurviveAJacksonRoundTrip() throws Exception {
        // Bucket records are persisted as JSON, so the configurations have to come back after a
        // restart, and a record written before the field existed has to still load.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        Bucket bucket = new Bucket("persisted");
        bucket.setMetricsConfigurations(new java.util.LinkedHashMap<>(
                Map.of("EntireBucket", "<Id>EntireBucket</Id>")));

        Bucket reloaded = mapper.readValue(mapper.writeValueAsString(bucket), Bucket.class);
        assertEquals("<Id>EntireBucket</Id>", reloaded.getMetricsConfigurations().get("EntireBucket"));

        Bucket legacy = mapper.readValue("{\"name\":\"legacy\"}", Bucket.class);
        assertNull(legacy.getMetricsConfigurations());
    }

    /**
     * Waits for the thread to reach the bucket monitor. The store is a ConcurrentHashMap, so
     * nothing else blocks it: this observes the interleaving rather than sleeping long enough to
     * hope for it, which keeps the test both instant and immune to a slow machine.
     */
    private static void awaitBlockedOnMonitor(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail("thread never reached the bucket monitor");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void deleteBucketTakesTheBucketMonitor() throws Exception {
        // Re-reading the record narrows the window but cannot close it: a delete landing between a
        // mutation's re-read and its write would still be undone. That interleaving is too narrow
        // to force from a test, so what is asserted here is the property that closes it — the
        // delete waits for whoever holds the record's monitor.
        var buckets = new InMemoryStorage<String, Bucket>();
        S3Service service = new S3Service(buckets, new InMemoryStorage<>(), tempDir.resolve("s3-monitor"), false);
        service.createBucket("monitor-bucket", "us-east-1");
        Bucket record = buckets.get("monitor-bucket").orElseThrow();

        Thread delete = new Thread(() -> service.deleteBucket("monitor-bucket"));
        synchronized (record) {
            delete.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (delete.getState() != Thread.State.BLOCKED) {
                assertTrue(buckets.get("monitor-bucket").isPresent(),
                        "DeleteBucket removed the record without waiting for the bucket monitor");
                if (System.nanoTime() > deadline) {
                    fail("DeleteBucket never reached the bucket monitor");
                }
                Thread.onSpinWait();
            }
        }
        delete.join(30_000);

        assertTrue(buckets.get("monitor-bucket").isEmpty());
    }

    @Test
    void aMetricsPutRacingABucketDeleteCannotRestoreTheBucket() throws Exception {
        // A put resolves the bucket record before it writes it back, so a delete landing in between
        // would be undone by the write. The interleaving is forced rather than raced for: the test
        // holds the bucket monitor, lets the put resolve the record and block on it, deletes the
        // bucket from the same thread, and only then releases.
        var buckets = new InMemoryStorage<String, Bucket>();
        S3Service service = new S3Service(buckets, new InMemoryStorage<>(), tempDir.resolve("s3-race"), false);
        service.createBucket("race-bucket", "us-east-1");
        Bucket record = buckets.get("race-bucket").orElseThrow();

        var putStarted = new java.util.concurrent.CountDownLatch(1);
        var thrownByPut = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread put = new Thread(() -> {
            putStarted.countDown();
            try {
                service.putBucketMetricsConfiguration("race-bucket", "id", "<Id>id</Id>");
            } catch (Exception e) {
                thrownByPut.set(e);
            }
        });

        synchronized (record) {
            put.start();
            putStarted.await();
            awaitBlockedOnMonitor(put);
            service.deleteBucket("race-bucket");
        }
        put.join(30_000);

        assertTrue(buckets.get("race-bucket").isEmpty(),
                "the deleted bucket was restored by the concurrent metrics put");
        assertEquals("NoSuchBucket",
                assertInstanceOf(AwsException.class, thrownByPut.get()).getErrorCode());
    }

    @Test
    void aMetricsPutCannotOverwriteABucketRecreatedWhileItWaited() throws Exception {
        // Presence is not enough: deleted and recreated under the same name, the store holds a
        // different record, and writing the resolved one back would replace the new bucket with
        // the old one's state.
        var buckets = new InMemoryStorage<String, Bucket>();
        S3Service service = new S3Service(buckets, new InMemoryStorage<>(), tempDir.resolve("s3-recreate"), false);
        service.createBucket("recreated-bucket", "us-east-1");
        Bucket original = buckets.get("recreated-bucket").orElseThrow();

        var putStarted = new java.util.concurrent.CountDownLatch(1);
        var thrownByPut = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread put = new Thread(() -> {
            putStarted.countDown();
            try {
                service.putBucketMetricsConfiguration("recreated-bucket", "stale", "<Id>stale</Id>");
            } catch (Exception e) {
                thrownByPut.set(e);
            }
        });

        synchronized (original) {
            put.start();
            putStarted.await();
            awaitBlockedOnMonitor(put);
            service.deleteBucket("recreated-bucket");
            service.createBucket("recreated-bucket", "us-east-1");
        }
        put.join(30_000);

        assertEquals("NoSuchBucket",
                assertInstanceOf(AwsException.class, thrownByPut.get()).getErrorCode());
        assertNotSame(original, buckets.get("recreated-bucket").orElseThrow(),
                "the recreated bucket was replaced by the record the put had resolved");
        assertNull(buckets.get("recreated-bucket").orElseThrow().getMetricsConfigurations(),
                "the recreated bucket inherited a configuration written against the old record");
    }

    @Test
    void concurrentMetricsConfigurationPutsAllSurvive() throws Exception {
        // Each put reads the configuration map, adds to it and writes it back, so without a shared
        // monitor concurrent puts of different ids overwrite each other's work.
        s3Service.createBucket("metrics-race", "us-east-1");
        int count = 24;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var submitted = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < count; i++) {
                String id = "config-" + i;
                submitted.add(pool.submit(() -> {
                    start.await();
                    s3Service.putBucketMetricsConfiguration("metrics-race", id, "<Id>" + id + "</Id>");
                    return null;
                }));
            }
            start.countDown();
            for (var future : submitted) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String listed = s3Service.listBucketMetricsConfigurations("metrics-race");
        for (int i = 0; i < count; i++) {
            assertTrue(listed.contains("<Id>config-" + i + "</Id>"),
                    "configuration config-" + i + " was lost by a concurrent put");
        }
    }
}
