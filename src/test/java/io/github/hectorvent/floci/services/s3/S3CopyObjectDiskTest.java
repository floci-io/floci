package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CopyObject in disk-storage mode, where the copy stores the source's pinned file instead of the
 * source's bytes, and reads that file only for an ETag or checksum it cannot carry over.
 */
class S3CopyObjectDiskTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;
    private InMemoryStorage<String, S3Object> objectStore;
    private Path dataRoot;

    @BeforeEach
    void setUp() {
        dataRoot = tempDir.resolve("s3");
        objectStore = new InMemoryStorage<>();
        s3Service = new S3Service(new InMemoryStorage<>(), objectStore, dataRoot, false);
        s3Service.createBucket("source-bucket", "us-east-1");
        s3Service.createBucket("dest-bucket", "us-east-1");
    }

    @Test
    void copyOfASinglePartObjectKeepsItsETagAndChecksumAndSharesItsFile() throws IOException {
        S3Object source = s3Service.putObject("source-bucket", "source.txt",
                "single part body".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        S3Object copy = s3Service.copyObject("source-bucket", "source.txt", "dest-bucket", "copy.txt",
                new CopyObjectOptions());

        assertEquals(source.getETag(), copy.getETag());
        assertEquals(source.getChecksum().getChecksumCRC64NVME(), copy.getChecksum().getChecksumCRC64NVME());
        assertArrayEquals("single part body".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("dest-bucket", "copy.txt").getData());
        List<Path> files = objectFiles();
        assertEquals(2, files.size(), "the source file and the copy's file: " + files);
        if (hardLinksSupported()) {
            assertTrue(Files.isSameFile(files.get(0), files.get(1)), "the copy should share the source's file");
        }
    }

    @Test
    void copyOfAMultipartObjectGetsTheMd5OfItsBytesAsItsETag() {
        byte[] part1 = "first part of the source, ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "second part of the source".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = s3Service.initiateMultipartUpload("source-bucket", "multipart.bin", null);
        s3Service.uploadPart("source-bucket", "multipart.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("source-bucket", "multipart.bin", upload.getUploadId(), 2, part2);
        S3Object source = s3Service.completeMultipartUpload("source-bucket", "multipart.bin",
                upload.getUploadId(), List.of(1, 2), null, null);
        assertTrue(source.getETag().endsWith("-2\""), "test setup: a multipart ETag " + source.getETag());

        S3Object copy = s3Service.copyObject("source-bucket", "multipart.bin", "dest-bucket", "copy.bin",
                new CopyObjectOptions());

        byte[] whole = concat(part1, part2);
        assertEquals(S3Object.computeETag(whole), copy.getETag());
        assertEquals(source.getChecksum().getChecksumCRC64NVME(), copy.getChecksum().getChecksumCRC64NVME(),
                "a full-object checksum carries over");
        assertArrayEquals(whole, s3Service.getObject("dest-bucket", "copy.bin").getData());
    }

    @Test
    void copyWithANewChecksumAlgorithmComputesItOverTheBytes() {
        byte[] body = "body to checksum again".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("source-bucket", "source.txt", body, "text/plain", Map.of());

        S3Object copy = s3Service.copyObject("source-bucket", "source.txt", "dest-bucket", "copy.txt",
                new CopyObjectOptions().withChecksumAlgorithm("SHA256"));

        assertEquals(S3Checksum.sha256Base64(body), copy.getChecksum().getChecksumSHA256());
        assertEquals(ChecksumType.FULL_OBJECT, copy.getChecksum().getChecksumType());
    }

    @Test
    void copyOfACompositeChecksumObjectGetsAFullObjectChecksumOfItsBytes() {
        byte[] part1 = "composite part one ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "composite part two".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = s3Service.initiateMultipartUpload("source-bucket", "composite.bin", null,
                null, null, null, null, null, null, null, null, "CRC32");
        s3Service.uploadPart("source-bucket", "composite.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("source-bucket", "composite.bin", upload.getUploadId(), 2, part2);
        S3Object source = s3Service.completeMultipartUpload("source-bucket", "composite.bin", upload.getUploadId(),
                List.of(1, 2), Map.of(1, S3Checksum.of(ChecksumAlgorithm.CRC32, part1),
                        2, S3Checksum.of(ChecksumAlgorithm.CRC32, part2)), null, null);
        assertEquals(ChecksumType.COMPOSITE, source.getChecksum().getChecksumType(), "test setup");

        S3Object copy = s3Service.copyObject("source-bucket", "composite.bin", "dest-bucket", "copy.bin",
                new CopyObjectOptions());

        assertEquals(S3Checksum.crc32Base64(concat(part1, part2)), copy.getChecksum().getChecksumCRC32());
        assertEquals(ChecksumType.FULL_OBJECT, copy.getChecksum().getChecksumType());
    }

    @Test
    void failedCopyLeavesNoPinnedFileBehind() throws IOException {
        s3Service.putObject("source-bucket", "source.txt", "source".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3Service.putObject("dest-bucket", "taken.txt", "already here".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        assertThrows(AwsException.class, () -> s3Service.copyObject("source-bucket", "source.txt",
                "dest-bucket", "taken.txt", new CopyObjectOptions().withIfNoneMatch("*")));

        try (Stream<Path> files = Files.walk(dataRoot)) {
            assertEquals(List.of(), files.filter(path -> path.getFileName().toString().contains(".tmp-")).toList());
        }
        assertArrayEquals("already here".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("dest-bucket", "taken.txt").getData());
    }

    @Test
    void copyIntoAVersionedBucketStoresTheNewVersionAndTheCurrentFile() {
        s3Service.putBucketVersioning("dest-bucket", "Enabled");
        s3Service.putObject("source-bucket", "source.txt", "versioned copy".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        S3Object copy = s3Service.copyObject("source-bucket", "source.txt", "dest-bucket", "copy.txt",
                new CopyObjectOptions());

        assertNotNull(copy.getVersionId());
        assertArrayEquals("versioned copy".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("dest-bucket", "copy.txt").getData());
        assertArrayEquals("versioned copy".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("dest-bucket", "copy.txt", copy.getVersionId()).getData());
    }

    @Test
    void copyOfASourceVersionCopiesThatVersionAfterTheKeyMovedOn() {
        s3Service.putBucketVersioning("source-bucket", "Enabled");
        S3Object first = s3Service.putObject("source-bucket", "source.txt", "first".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3Service.putObject("source-bucket", "source.txt", "second".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        s3Service.copyObject("source-bucket", "source.txt", "dest-bucket", "copy.txt", first.getVersionId(),
                new CopyObjectOptions());

        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("dest-bucket", "copy.txt").getData());
    }

    @Test
    void copySourceLargerThanFiveGibibytesIsAnInvalidRequest() {
        assertEquals(5_368_709_120L, S3Service.MAX_COPY_OBJECT_SOURCE_SIZE);
        S3Object atLimit = new S3Object("source-bucket", "at-limit.bin", 5_368_709_120L, null, "\"etag\"");
        assertDoesNotThrow(() -> S3Service.requireCopyableSize(atLimit));

        S3Object overLimit = new S3Object("source-bucket", "over-limit.bin", 5_368_709_121L, null, "\"etag\"");
        AwsException error = assertThrows(AwsException.class, () -> S3Service.requireCopyableSize(overLimit));

        assertEquals("InvalidRequest", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("The specified copy source is larger than the maximum allowable size for a copy source: 5368709120",
                error.getMessage());
    }

    @Test
    void oversizedSourceIsRejectedBeforeItsFileIsPinned() throws IOException {
        s3Service.putObject("source-bucket", "oversized.bin", "small".getBytes(StandardCharsets.UTF_8),
                "application/octet-stream", Map.of());
        // Metadata over 5 GiB on top of a file that is gone: a copy that went to the file first would
        // fail on the missing file instead of answering InvalidRequest from the metadata.
        objectStore.scan(key -> true).forEach(object -> object.setSize(S3Service.MAX_COPY_OBJECT_SOURCE_SIZE + 1));
        for (Path file : objectFiles()) {
            Files.delete(file);
        }

        AwsException error = assertThrows(AwsException.class, () -> s3Service.copyObject("source-bucket",
                "oversized.bin", "dest-bucket", "copy.bin", new CopyObjectOptions()));

        assertEquals("InvalidRequest", error.getErrorCode());
    }

    private List<Path> objectFiles() throws IOException {
        try (Stream<Path> files = Files.walk(dataRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".s3data")).toList();
        }
    }

    private boolean hardLinksSupported() throws IOException {
        Path probe = Files.createTempFile(tempDir, "link-probe", null);
        Path link = probe.resolveSibling(probe.getFileName() + ".link");
        try {
            Files.createLink(link, probe);
            return true;
        } catch (UnsupportedOperationException | IOException unsupported) {
            return false;
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(probe);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
