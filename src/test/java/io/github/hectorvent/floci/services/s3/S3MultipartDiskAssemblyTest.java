package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompleteMultipartUpload in disk-storage mode: byte-exact assembly in part order, the
 * composite-MD5 ETag, and the default full-object checksum, through S3Service's public interface.
 */
class S3MultipartDiskAssemblyTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;
    private Path dataRoot;

    @BeforeEach
    void setUp() {
        dataRoot = tempDir.resolve("s3");
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
        s3Service.createBucket("test-bucket", "us-east-1");
    }

    @Test
    void assembledObjectConcatenatesPartsInAscendingOrderRegardlessOfUploadOrder() {
        byte[] part1 = repeatingBytes((byte) 'A', 17_000);
        byte[] part2 = repeatingBytes((byte) 'B', 3);
        byte[] part3 = repeatingBytes((byte) 'C', 65_537);

        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "assembled.bin", null);
        // Upload out of order: the assembled result must still honour ascending part number, not upload order.
        s3Service.uploadPart("test-bucket", "assembled.bin", upload.getUploadId(), 3, part3);
        s3Service.uploadPart("test-bucket", "assembled.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "assembled.bin", upload.getUploadId(), 2, part2);

        s3Service.completeMultipartUpload("test-bucket", "assembled.bin", upload.getUploadId(),
                List.of(1, 2, 3), null, null);

        byte[] expected = concat(part1, part2, part3);
        assertArrayEquals(expected, s3Service.getObject("test-bucket", "assembled.bin").getData());
    }

    @Test
    void assembledObjectSkipsGapsWhenPartNumbersAreNonConsecutive() {
        byte[] part1 = "part-one".getBytes(StandardCharsets.UTF_8);
        byte[] part5 = "part-five".getBytes(StandardCharsets.UTF_8);

        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "gapped.bin", null);
        s3Service.uploadPart("test-bucket", "gapped.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "gapped.bin", upload.getUploadId(), 5, part5);

        s3Service.completeMultipartUpload("test-bucket", "gapped.bin", upload.getUploadId(),
                List.of(1, 5), null, null);

        assertArrayEquals(concat(part1, part5), s3Service.getObject("test-bucket", "gapped.bin").getData());
    }

    @Test
    void compositeETagIsTheMd5OfTheConcatenatedPartMd5s() throws NoSuchAlgorithmException {
        byte[] part1 = repeatingBytes((byte) 'X', 9_001);
        byte[] part2 = repeatingBytes((byte) 'Y', 4_096);

        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "etag.bin", null);
        s3Service.uploadPart("test-bucket", "etag.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "etag.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "etag.bin",
                upload.getUploadId(), List.of(1, 2), null, null);

        // Independently computed from the spec (MD5 of the concatenation of each part's own MD5),
        // not derived from any S3Service/S3Checksum code path.
        MessageDigest composite = MessageDigest.getInstance("MD5");
        composite.update(MessageDigest.getInstance("MD5").digest(part1));
        composite.update(MessageDigest.getInstance("MD5").digest(part2));
        String expectedETag = "\"" + HexFormat.of().formatHex(composite.digest()) + "-2\"";

        assertEquals(expectedETag, result.getETag());
        assertEquals(expectedETag, s3Service.getObject("test-bucket", "etag.bin").getETag());
    }

    @Test
    void defaultFullObjectChecksumCoversTheAssembledBytesInOrder() {
        byte[] part1 = repeatingBytes((byte) 'M', 12_345);
        byte[] part2 = repeatingBytes((byte) 'N', 6_789);

        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "checksum.bin", null);
        s3Service.uploadPart("test-bucket", "checksum.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "checksum.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "checksum.bin",
                upload.getUploadId(), List.of(1, 2), null, null);

        assertEquals(ChecksumType.FULL_OBJECT, result.getChecksum().getChecksumType());
        assertEquals(S3Checksum.crc64NvmeBase64(concat(part1, part2)),
                result.getChecksum().getChecksumCRC64NVME());
    }

    @Test
    void noObjectIsCreatedWhenAPartFileGoesMissingDuringAssembly() throws Exception {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "vanished.bin", null);
        s3Service.uploadPart("test-bucket", "vanished.bin", upload.getUploadId(), 1, "part1".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "vanished.bin", upload.getUploadId(), 2, "part2".getBytes(StandardCharsets.UTF_8));

        // Metadata (the Part record with its ETag) lives in memory, separate from the on-disk part
        // file that assembly reads; deleting only the file makes assembly fail after the
        // file-independent validation checks have passed.
        Path part2File = dataRoot.resolve(".multipart").resolve(upload.getUploadId()).resolve("2");
        assertTrue(Files.deleteIfExists(part2File), "test setup: part 2 file should exist before deletion");

        assertThrows(UncheckedIOException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "vanished.bin", upload.getUploadId(), List.of(1, 2), null, null));

        assertThrows(AwsException.class,
                () -> s3Service.getObject("test-bucket", "vanished.bin"));
    }

    @Test
    void partReadCopiesExactlyTheMeasuredBytes() throws IOException {
        byte[] dest = new byte[7];

        S3Service.readPart(new ByteArrayInputStream("abcde".getBytes(StandardCharsets.UTF_8)), dest, 1, 5, 1);

        assertArrayEquals(new byte[] {0, 'a', 'b', 'c', 'd', 'e', 0}, dest);
    }

    @Test
    void partThatGrewAfterBeingMeasuredFailsAssembly() {
        byte[] dest = new byte[5];

        assertThrows(IOException.class, () -> S3Service.readPart(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), dest, 0, 5, 1));
    }

    @Test
    void partThatShrankAfterBeingMeasuredFailsAssembly() {
        byte[] dest = new byte[5];

        assertThrows(IOException.class, () -> S3Service.readPart(
                new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)), dest, 0, 5, 1));
    }

    private static byte[] repeatingBytes(byte value, int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
