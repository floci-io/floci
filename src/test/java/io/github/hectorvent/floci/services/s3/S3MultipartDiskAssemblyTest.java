package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(Files.deleteIfExists(partFile(upload, 2)), "test setup: part 2 file should exist before deletion");

        AwsException error = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "vanished.bin", upload.getUploadId(), List.of(1, 2), null, null));

        assertEquals("InvalidPart", error.getErrorCode());

        assertThrows(AwsException.class,
                () -> s3Service.getObject("test-bucket", "vanished.bin"));
        assertEquals(List.of(), assembledFiles(upload), "the partial assembly should be removed");
    }

    @Test
    void failedStoreRemovesTheAssembledFileAndKeepsThePartsForARetry() throws Exception {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "public.bin", null,
                null, null, null, null, "public-read");
        s3Service.uploadPart("test-bucket", "public.bin", upload.getUploadId(), 1, "part1".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "public.bin", upload.getUploadId(), 2, "part2".getBytes(StandardCharsets.UTF_8));
        // The public ACL is only rejected once the parts are assembled, when the object is stored.
        s3Service.putPublicAccessBlock("test-bucket",
                "<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls></PublicAccessBlockConfiguration>");

        assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "public.bin", upload.getUploadId(), List.of(1, 2), null, null));

        assertEquals(List.of(), assembledFiles(upload), "the unstored assembly should be removed");
        s3Service.putPublicAccessBlock("test-bucket",
                "<PublicAccessBlockConfiguration><BlockPublicAcls>false</BlockPublicAcls></PublicAccessBlockConfiguration>");
        s3Service.completeMultipartUpload("test-bucket", "public.bin", upload.getUploadId(), List.of(1, 2), null, null);
        assertArrayEquals("part1part2".getBytes(StandardCharsets.UTF_8),
                s3Service.getObject("test-bucket", "public.bin").getData());
        assertFalse(Files.exists(partsDir(upload)), "a completed upload should leave no files behind");
    }

    @Test
    void versionedMultipartObjectIsStoredOnceAndKeepsItsBytesWhenTheKeyIsOverwritten() throws Exception {
        s3Service.putBucketVersioning("test-bucket", "Enabled");
        byte[] part1 = repeatingBytes((byte) 'V', 9_000);
        byte[] part2 = repeatingBytes((byte) 'W', 1_000);
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "versioned.bin", null);
        s3Service.uploadPart("test-bucket", "versioned.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "versioned.bin", upload.getUploadId(), 2, part2);

        String versionId = s3Service.completeMultipartUpload("test-bucket", "versioned.bin", upload.getUploadId(),
                List.of(1, 2), null, null).getVersionId();

        List<Path> stored = storedObjectFiles();
        assertEquals(2, stored.size(), "a versioned object has a versioned file and a current file: " + stored);
        assertTrue(Files.isSameFile(stored.get(0), stored.get(1)), "the two should be one file, not a copy");

        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        String replacementVersionId = s3Service.putObject("test-bucket", "versioned.bin", replacement,
                "text/plain", Map.of()).getVersionId();
        assertArrayEquals(replacement, s3Service.getObject("test-bucket", "versioned.bin").getData());
        assertArrayEquals(concat(part1, part2), s3Service.getObject("test-bucket", "versioned.bin", versionId).getData());

        s3Service.deleteObject("test-bucket", "versioned.bin", replacementVersionId);
        assertArrayEquals(concat(part1, part2), s3Service.getObject("test-bucket", "versioned.bin").getData());
    }

    @Test
    void mismatchedFullObjectChecksumIsRejectedBeforeAnyPartIsRead() throws Exception {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "bad-digest.bin", null);
        s3Service.uploadPart("test-bucket", "bad-digest.bin", upload.getUploadId(), 1, "part1".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "bad-digest.bin", upload.getUploadId(), 2, "part2".getBytes(StandardCharsets.UTF_8));
        // Without its part files, a Complete that reached assembly would fail with an I/O error instead.
        assertTrue(Files.deleteIfExists(partFile(upload, 1)), "test setup: part 1 file should exist before deletion");
        assertTrue(Files.deleteIfExists(partFile(upload, 2)), "test setup: part 2 file should exist before deletion");
        S3Checksum wrong = new S3Checksum();
        wrong.setChecksumCRC64NVME(S3Checksum.crc64NvmeBase64("other".getBytes(StandardCharsets.UTF_8)));

        AwsException error = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "bad-digest.bin", upload.getUploadId(), List.of(1, 2), null, wrong));

        assertEquals("BadDigest", error.getErrorCode());
    }

    @Test
    void reuploadedPartGetsANewFileAndTheReplacedFileIsRemoved() throws Exception {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "reupload.bin", null);
        s3Service.uploadPart("test-bucket", "reupload.bin", upload.getUploadId(), 1, "first".getBytes(StandardCharsets.UTF_8));
        Path firstFile = partFile(upload, 1);

        s3Service.uploadPart("test-bucket", "reupload.bin", upload.getUploadId(), 1, "other".getBytes(StandardCharsets.UTF_8));

        Path secondFile = partFile(upload, 1);
        assertNotEquals(firstFile, secondFile, "a part file is never rewritten in place");
        assertFalse(Files.exists(firstFile), "the replaced part's file should be removed");
        assertArrayEquals("other".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(secondFile));
        s3Service.completeMultipartUpload("test-bucket", "reupload.bin", upload.getUploadId(), List.of(1), null, null);
        assertArrayEquals("other".getBytes(StandardCharsets.UTF_8), s3Service.getObject("test-bucket", "reupload.bin").getData());
    }

    @Test
    void completeThatReadAReplacedPartsRecordFailsInsteadOfStoringTheNewBytes() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "raced.bin", null);
        s3Service.uploadPart("test-bucket", "raced.bin", upload.getUploadId(), 1, "first".getBytes(StandardCharsets.UTF_8));
        Part staleRecord = upload.getParts().get(1);
        s3Service.uploadPart("test-bucket", "raced.bin", upload.getUploadId(), 1, "other".getBytes(StandardCharsets.UTF_8));
        // A record whose bytes a same-size re-upload replaced. The upload lock keeps a Complete from
        // reading one, but if it ever did, it must fail rather than store the new bytes.
        upload.getParts().put(1, staleRecord);

        AwsException error = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "raced.bin", upload.getUploadId(), List.of(1), null, null));

        assertEquals("InvalidPart", error.getErrorCode());
        assertThrows(AwsException.class, () -> s3Service.getObject("test-bucket", "raced.bin"));
    }

    @Test
    void uploadPartCopyReadsTheRequestedRangeFromTheSourceFile() {
        byte[] source = new byte[200_000];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) (i % 251);
        }
        s3Service.putObject("test-bucket", "source.bin", source, "application/octet-stream", Map.of());
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "copy.bin", null);

        s3Service.uploadPartCopy("test-bucket", "copy.bin", upload.getUploadId(), 1,
                "test-bucket", "source.bin", null, "bytes=150000-150099");
        s3Service.completeMultipartUpload("test-bucket", "copy.bin", upload.getUploadId(), List.of(1), null, null);

        assertArrayEquals(Arrays.copyOfRange(source, 150_000, 150_100),
                s3Service.getObject("test-bucket", "copy.bin").getData());
    }

    @Test
    void restartRemovesTheFilesOfUploadsThatWereInProgress() throws Exception {
        s3Service.putObject("test-bucket", "kept.bin", "kept".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "interrupted.bin", null);
        s3Service.uploadPart("test-bucket", "interrupted.bin", upload.getUploadId(), 1, "part1".getBytes(StandardCharsets.UTF_8));
        // What a crash in the middle of CompleteMultipartUpload leaves next to the parts.
        Files.write(partsDir(upload).resolve("assembled-interrupted"), new byte[1024]);

        new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);

        assertFalse(Files.exists(dataRoot.resolve(".multipart")), "no upload outlives the process that held it");
        assertEquals(1, storedObjectFiles().size(), "stored objects are left alone");
    }

    @Test
    void resetRemovesTheFilesOfUploadsInProgress() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "reset.bin", null);
        s3Service.uploadPart("test-bucket", "reset.bin", upload.getUploadId(), 1, "part1".getBytes(StandardCharsets.UTF_8));

        s3Service.clear();

        assertFalse(Files.exists(dataRoot.resolve(".multipart")), "a reset forgets every upload, so their files go too");
    }

    @Test
    void appendedPartAddsExactlyItsRecordedBytes() throws IOException {
        Path partFile = Files.write(tempDir.resolve("part"), "abcde".getBytes(StandardCharsets.UTF_8));
        Path assembled = tempDir.resolve("assembled");

        try (FileChannel out = FileChannel.open(assembled, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(ByteBuffer.wrap("X".getBytes(StandardCharsets.UTF_8)));
            S3Service.appendPart(partFile, new Part(1, "\"etag\"", 5), out);
        }

        assertArrayEquals("Xabcde".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(assembled));
    }

    @Test
    void partThatGrewSinceItWasUploadedFailsAssembly() throws IOException {
        Path partFile = Files.write(tempDir.resolve("part"), "abcdef".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> appendToScratchFile(partFile, 5));
    }

    @Test
    void partThatShrankSinceItWasUploadedFailsAssembly() throws IOException {
        Path partFile = Files.write(tempDir.resolve("part"), "abcd".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> appendToScratchFile(partFile, 5));
    }

    private void appendToScratchFile(Path partFile, long recordedSize) throws IOException {
        try (FileChannel out = FileChannel.open(tempDir.resolve("scratch"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            S3Service.appendPart(partFile, new Part(1, "\"etag\"", recordedSize), out);
        }
    }

    private Path partsDir(MultipartUpload upload) {
        return dataRoot.resolve(".multipart").resolve(upload.getUploadId());
    }

    private Path partFile(MultipartUpload upload, int partNumber) {
        return partsDir(upload).resolve(upload.getParts().get(partNumber).getStorageId());
    }

    private List<Path> assembledFiles(MultipartUpload upload) throws IOException {
        try (Stream<Path> files = Files.list(partsDir(upload))) {
            return files.filter(path -> path.getFileName().toString().startsWith("assembled")).toList();
        }
    }

    private List<Path> storedObjectFiles() throws IOException {
        try (Stream<Path> files = Files.walk(dataRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".s3data")).toList();
        }
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
