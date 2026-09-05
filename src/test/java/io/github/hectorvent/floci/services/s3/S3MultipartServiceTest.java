package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class S3MultipartServiceTest {

    private S3Service s3Service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, true);
        s3Service.createBucket("test-bucket", "us-east-1");
    }

    @Test
    void initiateMultipartUpload() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "large-file.bin", "application/octet-stream");
        assertNotNull(upload.getUploadId());
        assertEquals("test-bucket", upload.getBucket());
        assertEquals("large-file.bin", upload.getKey());
        assertNotNull(upload.getInitiated());
    }

    @Test
    void initiateMultipartUploadNonExistentBucket() {
        assertThrows(AwsException.class, () ->
                s3Service.initiateMultipartUpload("no-bucket", "key", null));
    }

    @Test
    void uploadPart() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        byte[] data = "part-1-data".getBytes(StandardCharsets.UTF_8);
        String eTag = s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, data);
        assertNotNull(eTag);
        assertTrue(eTag.startsWith("\""));
        assertEquals(1, upload.getParts().size());
    }

    @Test
    void uploadPartInvalidNumber() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        assertThrows(AwsException.class, () ->
                s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 0, new byte[1]));
        assertThrows(AwsException.class, () ->
                s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 10001, new byte[1]));
    }

    @Test
    void uploadPartNonExistentUpload() {
        assertThrows(AwsException.class, () ->
                s3Service.uploadPart("test-bucket", "file.bin", "bad-id", 1, new byte[1]));
    }

    @Test
    void completeMultipartUpload() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", "text/plain",
                Map.of("owner", "team-a"), "STANDARD_IA");
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "part1".getBytes());
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 2, "part2".getBytes());

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "file.bin",
                upload.getUploadId(), List.of(1, 2), null, null);

        assertNotNull(result);
        assertEquals("text/plain", result.getContentType());
        assertEquals("STANDARD_IA", result.getStorageClass());
        assertEquals("team-a", result.getMetadata().get("owner"));
        assertEquals(2, result.getParts().size());
        // Verify the data is concatenated
        S3Object fetched = s3Service.getObject("test-bucket", "file.bin");
        assertEquals("part1part2", new String(fetched.getData()));
        // Composite ETag should end with -2 (number of parts)
        assertTrue(result.getETag().endsWith("-2\""), "ETag should be composite: " + result.getETag());
        // Without a checksum algorithm AWS attaches the default CRC64NVME full-object checksum
        assertEquals(ChecksumType.FULL_OBJECT, result.getChecksum().getChecksumType());
        assertEquals(S3Checksum.crc64NvmeBase64("part1part2".getBytes(StandardCharsets.UTF_8)),
                result.getChecksum().getChecksumCRC64NVME());
    }

    @Test
    void completeMultipartUploadAppliesTagging() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "tagged.bin", null,
                null, null, null, null, null, null, null, null, null,
                Map.of("token", "abc-123", "teamId", "42"));
        s3Service.uploadPart("test-bucket", "tagged.bin", upload.getUploadId(), 1, "part1".getBytes());

        s3Service.completeMultipartUpload("test-bucket", "tagged.bin", upload.getUploadId(), List.of(1), null, null);

        Map<String, String> tags = s3Service.getObjectTagging("test-bucket", "tagged.bin");
        assertEquals(2, tags.size());
        assertEquals("abc-123", tags.get("token"));
        assertEquals("42", tags.get("teamId"));
    }

    @Test
    void completeMultipartUploadWithoutTaggingLeavesObjectUntagged() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "untagged.bin", null);
        s3Service.uploadPart("test-bucket", "untagged.bin", upload.getUploadId(), 1, "part1".getBytes());

        s3Service.completeMultipartUpload("test-bucket", "untagged.bin", upload.getUploadId(), List.of(1), null, null);

        assertTrue(s3Service.getObjectTagging("test-bucket", "untagged.bin").isEmpty());
    }

    @Test
    void completeMultipartUploadMissingPart() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "part1".getBytes());

        assertThrows(AwsException.class, () ->
                s3Service.completeMultipartUpload("test-bucket", "file.bin",
                        upload.getUploadId(), List.of(1, 2), null, null));
    }

    @Test
    void abortMultipartUpload() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "data".getBytes());

        s3Service.abortMultipartUpload("test-bucket", "file.bin", upload.getUploadId());

        // Upload should no longer exist
        assertThrows(AwsException.class, () ->
                s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 2, "data".getBytes()));
    }

    @Test
    void listMultipartUploads() {
        s3Service.initiateMultipartUpload("test-bucket", "file1.bin", null);
        s3Service.initiateMultipartUpload("test-bucket", "file2.bin", null);

        List<MultipartUpload> uploads = s3Service.listMultipartUploads("test-bucket");
        assertEquals(2, uploads.size());
    }

    @Test
    void listMultipartUploadsEmpty() {
        List<MultipartUpload> uploads = s3Service.listMultipartUploads("test-bucket");
        assertTrue(uploads.isEmpty());
    }

    @Test
    void completeMultipartUploadVersioned() {
        s3Service.putBucketVersioning("test-bucket", "Enabled");
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "versioned.bin", "text/plain");
        s3Service.uploadPart("test-bucket", "versioned.bin", upload.getUploadId(), 1, "data".getBytes());

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "versioned.bin",
                upload.getUploadId(), List.of(1), null, null);

        assertNotNull(result.getVersionId(), "Versioned bucket should produce a versionId");
    }

    @Test
    void completeMultipartUploadCleansUp() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "data".getBytes());
        s3Service.completeMultipartUpload("test-bucket", "file.bin", upload.getUploadId(), List.of(1), null, null);

        // Should no longer be in active uploads
        List<MultipartUpload> uploads = s3Service.listMultipartUploads("test-bucket");
        assertTrue(uploads.isEmpty());
    }

    @Test
    void getObjectAttributesReturnsMultipartParts() {
        MultipartUpload upload = initiate("parts.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "parts.bin", upload.getUploadId(), 1, "abc".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "parts.bin", upload.getUploadId(), 2, "def".getBytes(StandardCharsets.UTF_8));
        s3Service.completeMultipartUpload("test-bucket", "parts.bin", upload.getUploadId(), List.of(1, 2),
                partChecksums(ChecksumAlgorithm.SHA256, "abc".getBytes(StandardCharsets.UTF_8), "def".getBytes(StandardCharsets.UTF_8)), null, null);

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "parts.bin", null,
                Set.of(ObjectAttributeName.OBJECT_PARTS, ObjectAttributeName.CHECKSUM),
                1, 0);

        assertNotNull(attributes.getChecksum());
        assertEquals(ChecksumType.COMPOSITE, attributes.getChecksum().getChecksumType());
        assertNotNull(attributes.getObjectParts());
        assertTrue(attributes.getObjectParts().isPartChecksumsAvailable());
        assertEquals(2, attributes.getObjectParts().getPartsCount());
        assertEquals(1, attributes.getObjectParts().getParts().size());
        assertTrue(attributes.getObjectParts().isTruncated());
        assertEquals(1, attributes.getObjectParts().getNextPartNumberMarker());
        assertEquals(S3Checksum.sha256Base64("abc".getBytes(StandardCharsets.UTF_8)),
                attributes.getObjectParts().getParts().get(0).getChecksum().getChecksumSHA256());
    }

    @Test
    void getObjectAttributesReportsOnlyThePartCountForFullObjectChecksums() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "full-parts.bin", "application/octet-stream");
        s3Service.uploadPart("test-bucket", "full-parts.bin", upload.getUploadId(), 1, "abc".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "full-parts.bin", upload.getUploadId(), 2, "def".getBytes(StandardCharsets.UTF_8));
        s3Service.completeMultipartUpload("test-bucket", "full-parts.bin", upload.getUploadId(), List.of(1, 2), null, null);

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "full-parts.bin", null,
                Set.of(ObjectAttributeName.OBJECT_PARTS, ObjectAttributeName.CHECKSUM), null, null);

        assertEquals(ChecksumType.FULL_OBJECT, attributes.getChecksum().getChecksumType());
        assertEquals(2, attributes.getObjectParts().getPartsCount());
        assertFalse(attributes.getObjectParts().isPartChecksumsAvailable());
        assertTrue(attributes.getObjectParts().getParts().isEmpty());
    }

    @Test
    void getObjectAttributesOmitsObjectPartsForSinglePartObjects() {
        s3Service.putObject("test-bucket", "single.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "single.txt", null,
                Set.of(ObjectAttributeName.OBJECT_PARTS, ObjectAttributeName.CHECKSUM), null, null);

        assertEquals(ChecksumType.FULL_OBJECT, attributes.getChecksum().getChecksumType());
        assertNull(attributes.getObjectParts());
    }

    @Test
    void completeMultipartUploadReturnsCompositeSha256Checksum() {
        byte[] part1 = "Part1Data-Hello".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "Part2Data-World".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("sha256.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "sha256.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "sha256.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "sha256.bin",
                upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1, part2), null, null);

        String expected = compositeSha256(part1, part2);
        assertTrue(expected.endsWith("-2"));
        assertEquals(expected, result.getChecksum().getChecksumSHA256());
        assertEquals(ChecksumType.COMPOSITE, result.getChecksum().getChecksumType());
        assertEquals(S3Checksum.sha256Base64(part1), result.getParts().get(0).getChecksum().getChecksumSHA256());
        assertEquals(S3Checksum.sha256Base64(part2), result.getParts().get(1).getChecksum().getChecksumSHA256());

        S3Object head = s3Service.getObjectMetadata("test-bucket", "sha256.bin", null);
        assertEquals(expected, head.getChecksum().getChecksumSHA256());

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "sha256.bin", null,
                Set.of(ObjectAttributeName.CHECKSUM), null, null);
        assertEquals(S3Checksum.withoutPartCount(expected), attributes.getChecksum().getChecksumSHA256());
        assertEquals(ChecksumType.COMPOSITE, attributes.getChecksum().getChecksumType());
    }

    @Test
    void singlePartMultipartUploadStillReturnsCompositeChecksum() {
        byte[] data = "only-part".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("single.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "single.bin", upload.getUploadId(), 1, data);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "single.bin",
                upload.getUploadId(), List.of(1), partChecksums(ChecksumAlgorithm.SHA256, data), null, null);

        assertEquals(compositeSha256(data), result.getChecksum().getChecksumSHA256());
        assertTrue(result.getChecksum().getChecksumSHA256().endsWith("-1"));
        assertEquals(ChecksumType.COMPOSITE, result.getChecksum().getChecksumType());
    }

    @Test
    void completeMultipartUploadReturnsCompositeCrc32Checksum() {
        byte[] part1 = "crc-part-1".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "crc-part-2".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("crc32.bin", "CRC32", null);
        s3Service.uploadPart("test-bucket", "crc32.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "crc32.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "crc32.bin",
                upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.CRC32, part1, part2), null, null);

        byte[] partChecksums = concat(Base64.getDecoder().decode(S3Checksum.crc32Base64(part1)),
                Base64.getDecoder().decode(S3Checksum.crc32Base64(part2)));
        assertEquals(S3Checksum.crc32Base64(partChecksums) + "-2", result.getChecksum().getChecksumCRC32());
        assertEquals(ChecksumType.COMPOSITE, result.getChecksum().getChecksumType());
    }

    @Test
    void completeMultipartUploadKeepsCrc64NvmeAsFullObjectChecksum() {
        byte[] part1 = "crc64-part-1".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "crc64-part-2".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("crc64.bin", "CRC64NVME", null);
        assertEquals(ChecksumType.FULL_OBJECT, upload.getChecksumType());
        s3Service.uploadPart("test-bucket", "crc64.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "crc64.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "crc64.bin",
                upload.getUploadId(), List.of(1, 2), null, null);

        assertEquals(S3Checksum.crc64NvmeBase64(concat(part1, part2)), result.getChecksum().getChecksumCRC64NVME());
        assertEquals(ChecksumType.FULL_OBJECT, result.getChecksum().getChecksumType());
    }

    @Test
    void completeMultipartUploadHonorsFullObjectTypeRequestedAtInitiation() {
        byte[] part1 = "full-part-1".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "full-part-2".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("crc32-full.bin", "CRC32", "FULL_OBJECT");
        s3Service.uploadPart("test-bucket", "crc32-full.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "crc32-full.bin", upload.getUploadId(), 2, part2);

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "crc32-full.bin",
                upload.getUploadId(), List.of(1, 2), null, null);

        assertEquals(S3Checksum.crc32Base64(concat(part1, part2)), result.getChecksum().getChecksumCRC32());
        assertEquals(ChecksumType.FULL_OBJECT, result.getChecksum().getChecksumType());
    }

    @Test
    void initiateMultipartUploadRejectsIncompatibleChecksumType() {
        AwsException sha = assertThrows(AwsException.class, () -> initiate("bad-sha.bin", "SHA256", "FULL_OBJECT"));
        assertEquals("InvalidRequest", sha.getErrorCode());
        AwsException crc64 = assertThrows(AwsException.class, () -> initiate("bad-crc64.bin", "CRC64NVME", "COMPOSITE"));
        assertEquals("InvalidRequest", crc64.getErrorCode());
        assertEquals(ChecksumType.COMPOSITE, initiate("crc32c.bin", "CRC32C", null).getChecksumType());
        assertNull(initiate("no-algorithm.bin", null, null).getChecksumType());

        AwsException typeAlone = assertThrows(AwsException.class, () -> initiate("type-alone.bin", null, "FULL_OBJECT"));
        assertEquals("InvalidRequest", typeAlone.getErrorCode());
        assertEquals("The x-amz-checksum-type header can only be used with the x-amz-checksum-algorithm header.",
                typeAlone.getMessage());
        AwsException unknownType = assertThrows(AwsException.class, () -> initiate("unknown-type.bin", null, "PARTIAL"));
        assertEquals("InvalidRequest", unknownType.getErrorCode());
        assertEquals("Value for x-amz-checksum-type header is invalid.", unknownType.getMessage());
    }

    @Test
    void completeMultipartUploadValidatesClientCompositeChecksum() {
        byte[] part1 = "Part1Data-Hello".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "Part2Data-World".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("validated.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "validated.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "validated.bin", upload.getUploadId(), 2, part2);
        String composite = compositeSha256(part1, part2);

        S3Checksum wrong = new S3Checksum();
        wrong.setChecksumSHA256("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=-2");
        AwsException mismatch = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "validated.bin", upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1, part2), "COMPOSITE", wrong));
        assertEquals("BadDigest", mismatch.getErrorCode());

        S3Checksum wrongPartCount = new S3Checksum();
        wrongPartCount.setChecksumSHA256(S3Checksum.withoutPartCount(composite) + "-5");
        assertEquals("BadDigest", assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "validated.bin", upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1, part2), null, wrongPartCount)).getErrorCode());

        S3Checksum withoutSuffix = new S3Checksum();
        withoutSuffix.setChecksumSHA256(S3Checksum.withoutPartCount(composite));
        S3Object result = s3Service.completeMultipartUpload("test-bucket", "validated.bin",
                upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1, part2), "COMPOSITE", withoutSuffix);
        assertEquals(composite, result.getChecksum().getChecksumSHA256());
    }

    @Test
    void completeMultipartUploadRejectsChecksumTypeDifferentFromInitiation() {
        byte[] data = "crc-data".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("crc32-mode.bin", "CRC32", null);
        s3Service.uploadPart("test-bucket", "crc32-mode.bin", upload.getUploadId(), 1, data);
        S3Checksum fullObject = new S3Checksum();
        fullObject.setChecksumCRC32(S3Checksum.crc32Base64(data));

        AwsException error = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "crc32-mode.bin", upload.getUploadId(), List.of(1), partChecksums(ChecksumAlgorithm.CRC32, data), "FULL_OBJECT", fullObject));

        assertEquals("InvalidRequest", error.getErrorCode());
        assertTrue(error.getMessage().contains("COMPOSITE checksum mode"), error.getMessage());
    }

    @Test
    void completeMultipartUploadRejectsMismatchedPartChecksum() {
        byte[] data = "part-data".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("bad-part.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "bad-part.bin", upload.getUploadId(), 1, data);
        S3Checksum wrongPart = new S3Checksum();
        wrongPart.setChecksumSHA256("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

        AwsException error = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "bad-part.bin", upload.getUploadId(), List.of(1), Map.of(1, wrongPart), null, null));
        assertEquals("InvalidPart", error.getErrorCode());

        S3Checksum rightPart = new S3Checksum();
        rightPart.setChecksumSHA256(S3Checksum.sha256Base64(data));
        S3Object result = s3Service.completeMultipartUpload("test-bucket", "bad-part.bin",
                upload.getUploadId(), List.of(1), Map.of(1, rightPart), null, null);
        assertEquals(compositeSha256(data), result.getChecksum().getChecksumSHA256());
    }

    @Test
    void completeMultipartUploadRequiresEveryPartChecksumOnCompositeUploads() {
        byte[] part1 = "Part1Data-Hello".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "Part2Data-World".getBytes(StandardCharsets.UTF_8);
        MultipartUpload upload = initiate("strict.bin", "SHA256", null);
        s3Service.uploadPart("test-bucket", "strict.bin", upload.getUploadId(), 1, part1);
        s3Service.uploadPart("test-bucket", "strict.bin", upload.getUploadId(), 2, part2);

        AwsException none = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "strict.bin", upload.getUploadId(), List.of(1, 2), null, null));
        assertEquals("InvalidRequest", none.getErrorCode());
        assertEquals("The upload was created using a sha256 checksum. The complete request must include the checksum "
                + "for each part. It was missing for part 1 in the request.", none.getMessage());

        AwsException second = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "strict.bin", upload.getUploadId(), List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1), null, null));
        assertTrue(second.getMessage().endsWith("It was missing for part 2 in the request."), second.getMessage());

        S3Checksum otherAlgorithm = new S3Checksum();
        otherAlgorithm.setChecksumCRC32(S3Checksum.crc32Base64(part1));
        AwsException other = assertThrows(AwsException.class, () -> s3Service.completeMultipartUpload(
                "test-bucket", "strict.bin", upload.getUploadId(), List.of(1, 2),
                Map.of(1, otherAlgorithm, 2, S3Checksum.of(ChecksumAlgorithm.SHA256, part2)), null, null));
        assertEquals("BadDigest", other.getErrorCode());
        assertEquals("The crc32 you specified for part 1 did not match what we received.", other.getMessage());

        S3Object result = s3Service.completeMultipartUpload("test-bucket", "strict.bin", upload.getUploadId(),
                List.of(1, 2), partChecksums(ChecksumAlgorithm.SHA256, part1, part2), null, null);
        assertEquals(compositeSha256(part1, part2), result.getChecksum().getChecksumSHA256());
    }

    private static Map<Integer, S3Checksum> partChecksums(ChecksumAlgorithm algorithm, byte[]... parts) {
        Map<Integer, S3Checksum> checksums = new HashMap<>();
        for (int i = 0; i < parts.length; i++) {
            checksums.put(i + 1, S3Checksum.of(algorithm, parts[i]));
        }
        return checksums;
    }

    private MultipartUpload initiate(String key, String checksumAlgorithm, String checksumType) {
        return s3Service.initiateMultipartUpload("test-bucket", key, null, null, null, null, null, null,
                null, null, null, checksumAlgorithm, checksumType, null);
    }

    private static String compositeSha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            MessageDigest composite = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                composite.update(digest.digest(part));
            }
            return Base64.getEncoder().encodeToString(composite.digest()) + "-" + parts.length;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
