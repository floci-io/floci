package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Object keys whose segments run past the 255-byte file-name limit, in disk-storage mode. The
 * body file gets a hashed name for the overlong segment while the object store keeps the key,
 * so listing still answers with the key the client used.
 */
class S3LongKeyDiskStorageTest {

    private static final int MAX_FILE_NAME_BYTES = 255;
    private static final String DATA_SUFFIX = ".s3data";
    private static final UploadChecksums NO_CHECKSUMS = new UploadChecksums(null, Map.of());

    @TempDir
    Path tempDir;

    private S3Service s3Service;
    private Path dataRoot;

    @BeforeEach
    void setUp() {
        dataRoot = tempDir.resolve("s3");
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
        s3Service.createBucket("bucket", "us-east-1");
    }

    @Test
    void putGetHeadDeleteAndListASingleSegmentKeyOverTheFileNameLimit() throws IOException {
        String key = "k".repeat(300);
        byte[] body = "a body for a long key".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, body, "text/plain", Map.of());

        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
        assertEquals(body.length, s3Service.headObject("bucket", key).getSize());
        List<String> listedKeys = s3Service.listObjects("bucket", null, null, 1000)
                .stream().map(S3Object::getKey).toList();
        assertEquals(List.of(key), listedKeys);
        assertEveryFileNameFitsTheByteLimit();

        s3Service.deleteObject("bucket", key);

        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                () -> s3Service.getObject("bucket", key)).getErrorCode());
    }

    @Test
    void theIssuesStreamedReproKeyIsStored() throws IOException {
        String key = "k".repeat(249);
        byte[] body = "streamed body".getBytes(StandardCharsets.UTF_8);

        S3Object streamed = s3Service.putObject("bucket", key, new ByteArrayInputStream(body),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions());

        assertEquals(body.length, streamed.getSize());
        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
        assertEquals(List.of(key), keysListedUnder(null));
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void aLastSegmentAtExactlyTheWritableLimitKeepsItsRawFileName() throws IOException {
        String key = "k".repeat(MAX_FILE_NAME_BYTES - DATA_SUFFIX.length());
        byte[] body = "at the raw limit".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, body, "text/plain", Map.of());

        assertTrue(Files.exists(rawObjectFile(key)),
                "a key that always fitted stays at its unencoded path");
        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
    }

    @Test
    void aMidSegmentAtExactlyTheByteLimitKeepsItsRawFileName() throws IOException {
        String midSegment = "m".repeat(MAX_FILE_NAME_BYTES);
        String key = "before/" + midSegment + "/after";
        byte[] body = "a mid segment at the limit".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, body, "text/plain", Map.of());

        assertTrue(Files.isDirectory(rawObjectFile(key).getParent()),
                "a segment that always fitted stays at its unencoded path");
        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
    }

    @Test
    void aLongSegmentAmongShortOnesRoundTrips() throws IOException {
        String key = "before/" + "k".repeat(300) + "/after";
        byte[] body = "a long segment in the middle".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, body, "text/plain", Map.of());

        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
        assertEquals(List.of(key), keysListedUnder(null));
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void multiByteCharactersInALongSegmentRoundTripByByteLength() throws IOException {
        String longSegment = "é".repeat(150);
        assertEquals(300, longSegment.getBytes(StandardCharsets.UTF_8).length,
                "test setup: the segment is over the limit only in bytes");
        String key = "dossier/" + longSegment + "/résumé";
        byte[] body = "multi-byte segment".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, body, "text/plain", Map.of());

        assertArrayEquals(body, s3Service.getObject("bucket", key).getData());
        assertEquals(List.of(key), keysListedUnder("dossier/"));
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void aVersionedBucketKeepsEachVersionOfALongKey() throws IOException {
        s3Service.putBucketVersioning("bucket", "Enabled");
        String key = "k".repeat(300);
        byte[] first = "the first version".getBytes(StandardCharsets.UTF_8);
        byte[] second = "the second version".getBytes(StandardCharsets.UTF_8);

        S3Object firstStored = s3Service.putObject("bucket", key, first, "text/plain", Map.of());
        s3Service.putObject("bucket", key, second, "text/plain", Map.of());

        assertArrayEquals(second, s3Service.getObject("bucket", key).getData());
        assertArrayEquals(first, s3Service.getObject("bucket", key, firstStored.getVersionId()).getData());
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void copyObjectToALongDestKeyRoundTrips() throws IOException {
        s3Service.putObject("bucket", "source.txt", "copy me".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        String destKey = "copied/" + "k".repeat(300);

        s3Service.copyObject("bucket", "source.txt", "bucket", destKey, new CopyObjectOptions());

        assertArrayEquals("copy me".getBytes(StandardCharsets.UTF_8), s3Service.getObject("bucket", destKey).getData());
        assertEquals(List.of("copied/" + "k".repeat(300), "source.txt"), keysListedUnder(null));
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void aMultipartUploadToALongKeyRoundTrips() throws IOException {
        String key = "multipart/" + "k".repeat(300);
        byte[] part1 = new byte[5 * 1024 * 1024];
        byte[] part2 = "the tail of the object".getBytes(StandardCharsets.UTF_8);

        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", key, null);
        s3Service.uploadPart("bucket", key, upload.getUploadId(), 1, part1);
        s3Service.uploadPart("bucket", key, upload.getUploadId(), 2, part2);
        S3Object stored = s3Service.completeMultipartUpload("bucket", key, upload.getUploadId(),
                List.of(1, 2), null, null);

        assertTrue(stored.getETag().endsWith("-2\""), "test setup: a multipart ETag " + stored.getETag());
        byte[] whole = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, whole, 0, part1.length);
        System.arraycopy(part2, 0, whole, part1.length, part2.length);
        assertArrayEquals(whole, s3Service.getObject("bucket", key).getData());
        assertEquals(List.of(key), keysListedUnder("multipart/"));
        assertEveryFileNameFitsTheByteLimit();
    }

    @Test
    void aSegmentShapedLikeAnEncodedOneNeverCollidesWithAnEncodedSegment() throws IOException {
        String longSegment = "k".repeat(300);
        String craftedRawSegment = "k".repeat(183) + "~" + Digests.sha256Hex(longSegment);
        assertTrue(craftedRawSegment.getBytes(StandardCharsets.UTF_8).length <= MAX_FILE_NAME_BYTES - DATA_SUFFIX.length(),
                "test setup: the crafted segment fits unencoded");
        byte[] longBody = "the long key's body".getBytes(StandardCharsets.UTF_8);
        byte[] craftedBody = "the crafted key's body".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", longSegment, longBody, "text/plain", Map.of());
        s3Service.putObject("bucket", craftedRawSegment, craftedBody, "text/plain", Map.of());

        assertArrayEquals(longBody, s3Service.getObject("bucket", longSegment).getData());
        assertArrayEquals(craftedBody, s3Service.getObject("bucket", craftedRawSegment).getData());
        List<String> listedKeys = s3Service.listObjects("bucket", null, null, 1000)
                .stream().map(S3Object::getKey).sorted().toList();
        assertEquals(List.of(longSegment, craftedRawSegment), listedKeys);
    }

    @Test
    void anEncodedSegmentIsIdempotentAcrossWritesAndReads() {
        String key = "k".repeat(300);
        byte[] first = "the first body".getBytes(StandardCharsets.UTF_8);
        byte[] second = "the second body".getBytes(StandardCharsets.UTF_8);

        s3Service.putObject("bucket", key, first, "text/plain", Map.of());
        s3Service.putObject("bucket", key, second, "text/plain", Map.of());

        assertArrayEquals(second, s3Service.getObject("bucket", key).getData());
        s3Service.deleteObject("bucket", key);
        s3Service.putObject("bucket", key, first, "text/plain", Map.of());
        assertArrayEquals(first, s3Service.getObject("bucket", key).getData());
    }

    private List<String> keysListedUnder(String prefix) {
        return s3Service.listObjects("bucket", prefix, null, 1000)
                .stream().map(S3Object::getKey).toList();
    }

    private Path rawObjectFile(String key) {
        return dataRoot.resolve(".accounts").resolve("000000000000").resolve("bucket")
                .resolve(key + DATA_SUFFIX);
    }

    private void assertEveryFileNameFitsTheByteLimit() throws IOException {
        try (Stream<Path> paths = Files.walk(dataRoot)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= MAX_FILE_NAME_BYTES,
                        "file name over the byte limit: " + name);
            }
        }
    }
}
