package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.GetObjectAttributesResult;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.ObjectAttributeName;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
                upload.getUploadId(), List.of(1, 2));

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
        assertEquals("COMPOSITE", result.getChecksum().getChecksumType());
    }

    @Test
    void completeMultipartUploadMissingPart() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "part1".getBytes());

        assertThrows(AwsException.class, () ->
                s3Service.completeMultipartUpload("test-bucket", "file.bin",
                        upload.getUploadId(), List.of(1, 2)));
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
                upload.getUploadId(), List.of(1));

        assertNotNull(result.getVersionId(), "Versioned bucket should produce a versionId");
    }

    @Test
    void completeMultipartUploadCleansUp() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "file.bin", null);
        s3Service.uploadPart("test-bucket", "file.bin", upload.getUploadId(), 1, "data".getBytes());
        s3Service.completeMultipartUpload("test-bucket", "file.bin", upload.getUploadId(), List.of(1));

        // Should no longer be in active uploads
        List<MultipartUpload> uploads = s3Service.listMultipartUploads("test-bucket");
        assertTrue(uploads.isEmpty());
    }

    @Test
    void getObjectAttributesReturnsMultipartParts() {
        MultipartUpload upload = s3Service.initiateMultipartUpload("test-bucket", "parts.bin", "application/octet-stream");
        s3Service.uploadPart("test-bucket", "parts.bin", upload.getUploadId(), 1, "abc".getBytes(StandardCharsets.UTF_8));
        s3Service.uploadPart("test-bucket", "parts.bin", upload.getUploadId(), 2, "def".getBytes(StandardCharsets.UTF_8));
        s3Service.completeMultipartUpload("test-bucket", "parts.bin", upload.getUploadId(), List.of(1, 2));

        GetObjectAttributesResult attributes = s3Service.getObjectAttributes("test-bucket", "parts.bin", null,
                Set.of(ObjectAttributeName.OBJECT_PARTS, ObjectAttributeName.CHECKSUM),
                1, 0);

        assertNotNull(attributes.getChecksum());
        assertEquals("COMPOSITE", attributes.getChecksum().getChecksumType());
        assertNotNull(attributes.getObjectParts());
        assertEquals(2, attributes.getObjectParts().getPartsCount());
        assertEquals(1, attributes.getObjectParts().getParts().size());
        assertTrue(attributes.getObjectParts().isTruncated());
        assertEquals(1, attributes.getObjectParts().getNextPartNumberMarker());
        assertNotNull(attributes.getObjectParts().getParts().get(0).getChecksum().getChecksumSHA256());
    }
}
