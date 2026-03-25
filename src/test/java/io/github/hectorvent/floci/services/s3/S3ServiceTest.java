package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

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

    @Test
    void listObjectsReturnsKeysInLexicographicOrder() {
        s3Service.createBucket("test-bucket", "us-east-1");
        // Insert in non-sorted order
        s3Service.putObject("test-bucket", "c.txt", "c".getBytes(), null, null);
        s3Service.putObject("test-bucket", "a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "b.txt", "b".getBytes(), null, null);

        List<S3Object> objects = s3Service.listObjects("test-bucket", null, null, 1000);
        assertEquals(3, objects.size());
        assertEquals("a.txt", objects.get(0).getKey());
        assertEquals("b.txt", objects.get(1).getKey());
        assertEquals("c.txt", objects.get(2).getKey());
    }

    @Test
    void listObjectsWithPrefixReturnsKeysInLexicographicOrder() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "dir/z.txt", "z".getBytes(), null, null);
        s3Service.putObject("test-bucket", "dir/a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "dir/m.txt", "m".getBytes(), null, null);
        s3Service.putObject("test-bucket", "other/x.txt", "x".getBytes(), null, null);

        List<S3Object> objects = s3Service.listObjects("test-bucket", "dir/", null, 1000);
        assertEquals(3, objects.size());
        assertEquals("dir/a.txt", objects.get(0).getKey());
        assertEquals("dir/m.txt", objects.get(1).getKey());
        assertEquals("dir/z.txt", objects.get(2).getKey());
    }

    @Test
    void listObjectsMaxKeysRespectsLexicographicOrder() {
        s3Service.createBucket("test-bucket", "us-east-1");
        s3Service.putObject("test-bucket", "c.txt", "c".getBytes(), null, null);
        s3Service.putObject("test-bucket", "a.txt", "a".getBytes(), null, null);
        s3Service.putObject("test-bucket", "b.txt", "b".getBytes(), null, null);

        List<S3Object> objects = s3Service.listObjects("test-bucket", null, null, 2);
        assertEquals(2, objects.size());
        assertEquals("a.txt", objects.get(0).getKey());
        assertEquals("b.txt", objects.get(1).getKey());
    }

    @Test
    void listObjectsReturnsNonAsciiKeysInUtf8LexicographicOrder() {
        s3Service.createBucket("non-ascii-bucket", "us-east-1");
        // U+E000 in UTF-8: EE 80 80 (BMP, Private Use Area)
        // U+10000 in UTF-8: F0 90 80 80 (supplementary plane)
        // Java String.compareTo (UTF-16): U+E000 (0xE000) > U+10000 (surrogate 0xD800)
        // UTF-8 unsigned byte order: U+E000 (0xEE) < U+10000 (0xF0)
        String keyE000 = "\uE000.txt";
        String key10000 = new String(Character.toChars(0x10000)) + ".txt";

        s3Service.putObject("non-ascii-bucket", key10000, "b".getBytes(StandardCharsets.UTF_8), null, null);
        s3Service.putObject("non-ascii-bucket", keyE000, "a".getBytes(StandardCharsets.UTF_8), null, null);

        List<S3Object> objects = s3Service.listObjects("non-ascii-bucket", null, null, 1000);
        assertEquals(2, objects.size());
        // UTF-8 byte order: U+E000 (EE 80 80) < U+10000 (F0 90 80 80)
        assertEquals(keyE000, objects.get(0).getKey());
        assertEquals(key10000, objects.get(1).getKey());
    }

    @Test
    void listObjectsDirectoryBucketDoesNotSort() {
        // Use a LinkedHashMap-backed storage that preserves insertion order
        OrderedStorage<String, S3Object> objectStore = new OrderedStorage<>();
        S3Service orderedService = new S3Service(new InMemoryStorage<>(), objectStore, tempDir.resolve("s3-ordered"));
        orderedService.createBucket("my-bucket--x-s3", "us-east-1");
        // Insert in reverse lexicographic order
        orderedService.putObject("my-bucket--x-s3", "c.txt", "c".getBytes(), null, null);
        orderedService.putObject("my-bucket--x-s3", "b.txt", "b".getBytes(), null, null);
        orderedService.putObject("my-bucket--x-s3", "a.txt", "a".getBytes(), null, null);

        List<S3Object> objects = orderedService.listObjects("my-bucket--x-s3", null, null, 1000);
        assertEquals(3, objects.size());
        // Directory bucket: sorting is skipped, so insertion order (c, b, a) is preserved
        assertEquals("c.txt", objects.get(0).getKey());
        assertEquals("b.txt", objects.get(1).getKey());
        assertEquals("a.txt", objects.get(2).getKey());
    }

    @Test
    void isDirectoryBucket() {
        assertTrue(S3Service.isDirectoryBucket("my-bucket--x-s3"));
        assertFalse(S3Service.isDirectoryBucket("my-bucket"));
        assertFalse(S3Service.isDirectoryBucket(null));
    }

    /**
     * A storage backend backed by LinkedHashMap to preserve insertion order in scan().
     */
    static class OrderedStorage<K, V> implements StorageBackend<K, V> {
        private final LinkedHashMap<K, V> store = new LinkedHashMap<>();

        @Override public void put(K key, V value) { store.put(key, value); }
        @Override public Optional<V> get(K key) { return Optional.ofNullable(store.get(key)); }
        @Override public void delete(K key) { store.remove(key); }
        @Override public List<V> scan(Predicate<K> keyFilter) {
            List<V> result = new ArrayList<>();
            store.forEach((k, v) -> { if (keyFilter.test(k)) result.add(v); });
            return result;
        }
        @Override public Set<K> keys() { return Collections.unmodifiableSet(store.keySet()); }
        @Override public void flush() { }
        @Override public void load() { }
        @Override public void clear() { store.clear(); }
    }
}
