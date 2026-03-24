package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        Bucket bucket = s3Service.createBucket("test-bucket");
        assertEquals("test-bucket", bucket.getName());
        assertNotNull(bucket.getCreationDate());
    }

    @Test
    void createDuplicateBucketThrows() {
        s3Service.createBucket("test-bucket");
        assertThrows(AwsException.class, () -> s3Service.createBucket("test-bucket"));
    }

    @Test
    void deleteBucket() {
        s3Service.createBucket("test-bucket");
        s3Service.deleteBucket("test-bucket");
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("test-bucket"));
    }

    @Test
    void deleteNonEmptyBucketThrows() {
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "hello".getBytes(), "text/plain", null);
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("test-bucket"));
    }

    @Test
    void deleteNonExistentBucketThrows() {
        assertThrows(AwsException.class, () -> s3Service.deleteBucket("nonexistent"));
    }

    @Test
    void listBuckets() {
        s3Service.createBucket("bucket-a");
        s3Service.createBucket("bucket-b");

        List<Bucket> buckets = s3Service.listBuckets();
        assertEquals(2, buckets.size());
    }

    @Test
    void putObjectLastModifiedHasSecondPrecision() {
        s3Service.createBucket("test-bucket");
        S3Object obj = s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        assertEquals(0, obj.getLastModified().getNano());
    }

    @Test
    void putAndGetObject() {
        s3Service.createBucket("test-bucket");
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
        s3Service.createBucket("test-bucket");
        byte[] data = "file content".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("test-bucket", "docs/readme.txt", data, "text/plain", null);

        Path filePath = tempDir.resolve("s3/test-bucket/docs/readme.txt");
        assertTrue(Files.exists(filePath));
        assertArrayEquals(data, assertDoesNotThrow(() -> Files.readAllBytes(filePath)));
    }

    @Test
    void deleteObjectRemovesFileFromDisk() {
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);

        Path filePath = tempDir.resolve("s3/test-bucket/file.txt");
        assertTrue(Files.exists(filePath));

        s3Service.deleteObject("test-bucket", "file.txt");
        assertFalse(Files.exists(filePath));
    }

    @Test
    void deleteBucketRemovesDirectory() {
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        s3Service.deleteObject("test-bucket", "file.txt");
        s3Service.deleteBucket("test-bucket");

        assertFalse(Files.exists(tempDir.resolve("s3/test-bucket")));
    }

    @Test
    void getObjectNotFoundThrows() {
        s3Service.createBucket("test-bucket");
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
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "data".getBytes(), null, null);
        s3Service.deleteObject("test-bucket", "file.txt");

        assertThrows(AwsException.class, () ->
                s3Service.getObject("test-bucket", "file.txt"));
    }

    @Test
    void listObjects() {
        s3Service.createBucket("test-bucket");
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
        s3Service.createBucket("source-bucket");
        s3Service.createBucket("dest-bucket");
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
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "original.txt", "data".getBytes(), null, null);
        s3Service.copyObject("test-bucket", "original.txt", "test-bucket", "copy.txt");

        assertNotNull(s3Service.getObject("test-bucket", "copy.txt"));
    }

    @Test
    void headObject() {
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "hello".getBytes(), "text/plain", null);

        S3Object head = s3Service.headObject("test-bucket", "file.txt");
        assertEquals(5, head.getSize());
        assertEquals("text/plain", head.getContentType());
    }

    @Test
    void putObjectOverwrites() {
        s3Service.createBucket("test-bucket");
        s3Service.putObject("test-bucket", "file.txt", "v1".getBytes(), null, null);
        s3Service.putObject("test-bucket", "file.txt", "v2".getBytes(), null, null);

        S3Object obj = s3Service.getObject("test-bucket", "file.txt");
        assertArrayEquals("v2".getBytes(), obj.getData());
    }
}
