package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PutObject and UploadPart bodies read as a stream, as the controller hands them over, so a
 * disk-backed upload is never held in memory whole.
 */
class S3StreamedUploadTest {

    private static final byte[] BODY = "a body that arrives as a stream".getBytes(StandardCharsets.UTF_8);
    private static final UploadChecksums NO_CHECKSUMS = new UploadChecksums(null, Map.of());

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
        s3Service.createBucket("bucket", "us-east-1");
    }

    @Test
    void aStreamedPutStoresWhatTheBytePathStores() {
        S3Object fromBytes = s3Service.putObject("bucket", "bytes.txt", BODY, "text/plain", Map.of());

        S3Object streamed = s3Service.putObject("bucket", "streamed.txt", new ByteArrayInputStream(BODY),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions());

        assertEquals(fromBytes.getETag(), streamed.getETag());
        assertEquals(fromBytes.getChecksum().getChecksumCRC64NVME(), streamed.getChecksum().getChecksumCRC64NVME());
        assertEquals(ChecksumType.FULL_OBJECT, streamed.getChecksum().getChecksumType());
        assertEquals(BODY.length, streamed.getSize());
        assertArrayEquals(BODY, s3Service.getObject("bucket", "streamed.txt").getData());
        assertNothingStaged();
    }

    @Test
    void aStreamedPutStoresTheDeclaredAlgorithmsChecksum() {
        S3Object streamed = s3Service.putObject("bucket", "sha.txt", new ByteArrayInputStream(BODY),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions().withChecksumAlgorithm("SHA256"));

        assertEquals(ChecksumAlgorithm.SHA256.compute(BODY), streamed.getChecksum().getChecksumSHA256());
        assertNull(streamed.getChecksum().getChecksumCRC64NVME());
    }

    @Test
    void aStreamedPutIntoAVersionedBucketKeepsEachVersion() {
        s3Service.putBucketVersioning("bucket", "Enabled");
        byte[] second = "the second version".getBytes(StandardCharsets.UTF_8);
        S3Object first = s3Service.putObject("bucket", "versioned.txt", new ByteArrayInputStream(BODY),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions());
        s3Service.putObject("bucket", "versioned.txt", new ByteArrayInputStream(second),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions());

        assertArrayEquals(second, s3Service.getObject("bucket", "versioned.txt").getData());
        assertArrayEquals(BODY, s3Service.getObject("bucket", "versioned.txt", first.getVersionId()).getData());
        assertNothingStaged();
    }

    @Test
    void aBodyThatFailsItsChecksumIsNotStored() {
        UploadChecksums wrong = new UploadChecksums(null,
                Map.of(ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32.compute("something else".getBytes(StandardCharsets.UTF_8))));

        AwsException error = assertThrows(AwsException.class, () -> s3Service.putObject("bucket", "bad.txt",
                new ByteArrayInputStream(BODY), wrong, "text/plain", Map.of(), new PutObjectOptions()));

        assertEquals("BadDigest", error.getErrorCode());
        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                () -> s3Service.headObject("bucket", "bad.txt")).getErrorCode());
        assertNothingStaged();
    }

    @Test
    void aBodyThatMatchesItsContentMd5AndChecksumIsStored() throws Exception {
        String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(BODY));
        UploadChecksums right = new UploadChecksums(md5, Map.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.SHA1.compute(BODY)));

        s3Service.putObject("bucket", "good.txt", new ByteArrayInputStream(BODY), right,
                "text/plain", Map.of(), new PutObjectOptions());

        assertArrayEquals(BODY, s3Service.getObject("bucket", "good.txt").getData());
    }

    @Test
    void aWriteThatCannotSucceedFailsBeforeTheBodyIsRead() {
        s3Service.putObject("bucket", "existing.txt", BODY, "text/plain", Map.of());

        assertEquals("PreconditionFailed", assertThrows(AwsException.class, () -> s3Service.putObject("bucket",
                "existing.txt", unreadable(), NO_CHECKSUMS, "text/plain", Map.of(),
                new PutObjectOptions().withIfNoneMatch("*"))).getErrorCode());
        assertEquals("NoSuchBucket", assertThrows(AwsException.class, () -> s3Service.putObject("missing-bucket",
                "key.txt", unreadable(), NO_CHECKSUMS, "text/plain", Map.of(),
                new PutObjectOptions())).getErrorCode());
        assertEquals("InvalidDigest", assertThrows(AwsException.class, () -> s3Service.putObject("bucket",
                "key.txt", unreadable(), new UploadChecksums("not-an-md5", Map.of()), "text/plain", Map.of(),
                new PutObjectOptions())).getErrorCode());
        assertEquals("InvalidArgument", assertThrows(AwsException.class, () -> s3Service.putObject("bucket",
                "key.txt", unreadable(), NO_CHECKSUMS, "text/plain", Map.of(),
                new PutObjectOptions().withChecksumAlgorithm("NOT-AN-ALGORITHM"))).getErrorCode());
    }

    @Test
    void aBodyThatBreaksPartwayLeavesNothingBehind() {
        assertThrows(UncheckedIOException.class, () -> s3Service.putObject("bucket", "broken.txt",
                breaksAfter(BODY), NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions()));

        AwsException framing = assertThrows(AwsException.class, () -> s3Service.putObject("bucket", "framing.txt",
                new AwsChunkedInputStream(new ByteArrayInputStream("10\r\nonly-five\r\n".getBytes(StandardCharsets.US_ASCII))),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions()));

        assertEquals("IncompleteBody", framing.getErrorCode());
        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                () -> s3Service.headObject("bucket", "broken.txt")).getErrorCode());
        assertNothingStaged();
    }

    @Test
    void aStreamedPartStoresWhatTheBytePathStores() {
        byte[] part2 = "and a second part".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "multipart.bin", null);
        Part fromBytes = s3Service.storePart("bucket", "multipart.bin", upload.getUploadId(), 1, BODY, null, null, null);

        Part streamed = s3Service.storePart("bucket", "multipart.bin", upload.getUploadId(), 2,
                new ByteArrayInputStream(part2), NO_CHECKSUMS, null, null, null);
        Part streamedAgain = s3Service.storePart("bucket", "multipart.bin", upload.getUploadId(), 1,
                new ByteArrayInputStream(BODY), NO_CHECKSUMS, null, null, null);

        assertEquals(fromBytes.getETag(), streamedAgain.getETag());
        assertEquals(fromBytes.getChecksum().getChecksumCRC64NVME(), streamedAgain.getChecksum().getChecksumCRC64NVME());
        assertEquals(part2.length, streamed.getSize());
        s3Service.completeMultipartUpload("bucket", "multipart.bin", upload.getUploadId(), List.of(1, 2), null, null);
        byte[] whole = new byte[BODY.length + part2.length];
        System.arraycopy(BODY, 0, whole, 0, BODY.length);
        System.arraycopy(part2, 0, whole, BODY.length, part2.length);
        assertArrayEquals(whole, s3Service.getObject("bucket", "multipart.bin").getData());
        assertNothingStaged();
    }

    @Test
    void aPartThatFailsItsChecksumIsNotRecorded() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "multipart.bin", null);
        UploadChecksums wrong = new UploadChecksums(null,
                Map.of(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.SHA256.compute("other".getBytes(StandardCharsets.UTF_8))));

        assertEquals("BadDigest", assertThrows(AwsException.class, () -> s3Service.storePart("bucket",
                "multipart.bin", upload.getUploadId(), 1, new ByteArrayInputStream(BODY), wrong,
                null, null, null)).getErrorCode());

        assertTrue(s3Service.getMultipartUpload("bucket", "multipart.bin", upload.getUploadId()).getParts().isEmpty());
        assertNothingStaged();
    }

    @Test
    void aPartForAnUploadThatCannotTakeItFailsBeforeItsBodyIsRead() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "multipart.bin", null);

        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> s3Service.storePart("bucket",
                "multipart.bin", "no-such-upload", 1, unreadable(), NO_CHECKSUMS, null, null, null)).getErrorCode());
        assertEquals("InvalidArgument", assertThrows(AwsException.class, () -> s3Service.storePart("bucket",
                "multipart.bin", upload.getUploadId(), 10001, unreadable(), NO_CHECKSUMS, null, null, null)).getErrorCode());
    }

    @Test
    void anUploadAbortedWhileAPartArrivesFailsThePartAndLeavesNothingBehind() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "multipart.bin", null);
        InputStream body = onFirstRead(BODY,
                () -> s3Service.abortMultipartUpload("bucket", "multipart.bin", upload.getUploadId()));

        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> s3Service.storePart("bucket",
                "multipart.bin", upload.getUploadId(), 1, body, NO_CHECKSUMS, null, null, null)).getErrorCode());

        assertFalse(Files.exists(dataRoot.resolve(".multipart").resolve(upload.getUploadId())));
        assertNothingStaged();
    }

    @Test
    void aPartStillArrivingDoesNotHoldUpDeletingItsBucket() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "multipart.bin", null);
        // Deleting the bucket takes its multipart lock exclusively, so this would never return if the
        // part held that lock while its body arrived.
        InputStream body = onFirstRead(BODY, () -> s3Service.deleteBucket("bucket"));

        AwsException error = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(AwsException.class,
                () -> s3Service.storePart("bucket", "multipart.bin", upload.getUploadId(), 1, body,
                        NO_CHECKSUMS, null, null, null)));

        assertTrue(List.of("NoSuchUpload", "NoSuchBucket").contains(error.getErrorCode()), error.getErrorCode());
        assertNothingStaged();
    }

    @Test
    void anUploadPartCopyStreamsItsRangeIntoThePart() {
        byte[] source = new byte[300_000];
        new Random(5034).nextBytes(source);
        s3Service.putObject("bucket", "source.bin", source, "application/octet-stream", Map.of());
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "copy.bin", null);

        String rangeETag = s3Service.uploadPartCopy("bucket", "copy.bin", upload.getUploadId(), 1,
                "bucket", "source.bin", null, "bytes=1000-200999");
        String wholeETag = s3Service.uploadPartCopy("bucket", "copy.bin", upload.getUploadId(), 2,
                "bucket", "source.bin", null, null);

        byte[] range = Arrays.copyOfRange(source, 1000, 201_000);
        assertEquals(S3Object.computeETag(range), rangeETag);
        assertEquals(S3Object.computeETag(source), wholeETag);
        s3Service.completeMultipartUpload("bucket", "copy.bin", upload.getUploadId(), List.of(1, 2), null, null);
        byte[] whole = new byte[range.length + source.length];
        System.arraycopy(range, 0, whole, 0, range.length);
        System.arraycopy(source, 0, whole, range.length, source.length);
        assertArrayEquals(whole, s3Service.getObject("bucket", "copy.bin").getData());
        assertNothingStaged();
    }

    @Test
    void anUploadPartCopyIntoAMissingUploadFails() {
        s3Service.putObject("bucket", "source.bin", BODY, "text/plain", Map.of());

        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> s3Service.uploadPartCopy("bucket",
                "copy.bin", "no-such-upload", 1, "bucket", "source.bin", null, "bytes=0-9")).getErrorCode());
        assertNothingStaged();
    }

    @Test
    void anUploadPartCopyOverFiveGibibytesIsEntityTooLarge() throws IOException {
        assertEquals(5_368_709_120L, S3Service.MAX_PART_SIZE);
        long overLimit = S3Service.MAX_PART_SIZE + 1;
        sparseSource("source.bin", overLimit);
        MultipartUpload upload = s3Service.initiateMultipartUpload("bucket", "copy.bin", null);

        for (String range : Arrays.asList(null, "bytes=0-" + S3Service.MAX_PART_SIZE)) {
            AwsException error = assertThrows(AwsException.class, () -> s3Service.uploadPartCopy("bucket",
                    "copy.bin", upload.getUploadId(), 1, "bucket", "source.bin", null, range), "range " + range);

            assertEquals("EntityTooLarge", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertEquals("Your proposed upload exceeds the maximum allowed object size.", error.getMessage());
        }
        assertTrue(s3Service.getMultipartUpload("bucket", "copy.bin", upload.getUploadId()).getParts().isEmpty());
        assertNothingStaged();
    }

    @Test
    void anUploadPartCopyOfExactlyFiveGibibytesIsWithinTheLimit() throws IOException {
        sparseSource("source.bin", S3Service.MAX_PART_SIZE + 1);

        // A missing upload is only found once the size passed, and before any of the source is read.
        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> s3Service.uploadPartCopy("bucket",
                "copy.bin", "no-such-upload", 1, "bucket", "source.bin", null,
                "bytes=1-" + S3Service.MAX_PART_SIZE)).getErrorCode());
    }

    /** A stored object of {@code size} bytes whose file is sparse, so it takes almost no disk. */
    private void sparseSource(String key, long size) throws IOException {
        s3Service.putObject("bucket", key, new byte[] {1}, "application/octet-stream", Map.of());
        objectStore.scan(storeKey -> true).stream()
                .filter(object -> "bucket".equals(object.getBucketName()) && key.equals(object.getKey()))
                .forEach(object -> object.setSize(size));
        try (Stream<Path> files = Files.walk(dataRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals(key + ".s3data")).toList()) {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(new byte[] {0}), size - 1);
                }
            }
        }
    }

    @Test
    void memoryModeReadsTheStreamIntoTheBytePaths() {
        S3Service memory = memoryService();
        S3Object fromBytes = memory.putObject("bucket", "bytes.txt", BODY, "text/plain", Map.of());

        S3Object streamed = memory.putObject("bucket", "streamed.txt", new ByteArrayInputStream(BODY),
                NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions());
        MultipartUpload upload = memory.initiateMultipartUpload("bucket", "multipart.bin", null);
        memory.storePart("bucket", "multipart.bin", upload.getUploadId(), 1, new ByteArrayInputStream(BODY),
                NO_CHECKSUMS, null, null, null);
        memory.completeMultipartUpload("bucket", "multipart.bin", upload.getUploadId(), List.of(1), null, null);

        assertEquals(fromBytes.getETag(), streamed.getETag());
        assertArrayEquals(BODY, memory.getObject("bucket", "streamed.txt").getData());
        assertArrayEquals(BODY, memory.getObject("bucket", "multipart.bin").getData());
        UploadChecksums wrong = new UploadChecksums(null,
                Map.of(ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.CRC32C.compute("other".getBytes(StandardCharsets.UTF_8))));
        assertEquals("BadDigest", assertThrows(AwsException.class, () -> memory.putObject("bucket", "bad.txt",
                new ByteArrayInputStream(BODY), wrong, "text/plain", Map.of(), new PutObjectOptions())).getErrorCode());
    }

    @Test
    void aBucketRecreatedWhileABodyArrivesDoesNotReceiveTheObject() {
        for (S3Service service : List.of(s3Service, memoryService())) {
            service.createBucket("recreated", "us-east-1");
            // The request was checked against the first bucket; the second has its own policy.
            InputStream body = onFirstRead(BODY, () -> {
                service.deleteBucket("recreated");
                service.createBucket("recreated", "us-east-1");
            });

            assertEquals("NoSuchBucket", assertThrows(AwsException.class, () -> service.putObject("recreated",
                    "object.txt", body, NO_CHECKSUMS, "text/plain", Map.of(), new PutObjectOptions())).getErrorCode());

            assertEquals("NoSuchKey", assertThrows(AwsException.class,
                    () -> service.headObject("recreated", "object.txt")).getErrorCode());
        }
        assertNothingStaged();
    }

    @Test
    void memoryModeChecksAPartsUploadBeforeReadingItsBody() {
        S3Service memory = memoryService();
        MultipartUpload upload = memory.initiateMultipartUpload("bucket", "multipart.bin", null);

        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> memory.storePart("bucket",
                "multipart.bin", "no-such-upload", 1, unreadable(), NO_CHECKSUMS, null, null, null)).getErrorCode());
        assertEquals("InvalidArgument", assertThrows(AwsException.class, () -> memory.storePart("bucket",
                "multipart.bin", upload.getUploadId(), 0, unreadable(), NO_CHECKSUMS, null, null, null)).getErrorCode());
    }

    private S3Service memoryService() {
        S3Service memory = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, true);
        memory.createBucket("bucket", "us-east-1");
        return memory;
    }

    private void assertNothingStaged() {
        Path incoming = dataRoot.resolve(".multipart").resolve(".incoming");
        if (!Files.isDirectory(incoming)) {
            return;
        }
        try (Stream<Path> files = Files.list(incoming)) {
            assertEquals(List.of(), files.toList(), "staged bodies left behind");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A body the write should never get to reading. */
    private static InputStream unreadable() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the body was read");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                throw new AssertionError("the body was read");
            }
        };
    }

    /** {@code data}, then a failure, as when a client disconnects partway. */
    private static InputStream breaksAfter(byte[] data) {
        return new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read < 0) {
                    throw new IOException("connection reset");
                }
                return read;
            }
        };
    }

    /** {@code data}, running {@code action} the first time any of it is read. */
    private static InputStream onFirstRead(byte[] data, Runnable action) {
        return new ByteArrayInputStream(data) {
            private boolean started;

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                if (!started) {
                    started = true;
                    action.run();
                }
                return super.read(buffer, offset, length);
            }
        };
    }
}
