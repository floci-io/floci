package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Object Lock protects versions, not keys: writes that add a new current version (delete marker, put, copy,
 * multipart complete) must succeed over a locked version and leave that version intact and still locked.
 */
class S3ObjectLockVersionedWriteTest {

    private static final String BUCKET = "lock-bucket";
    private static final byte[] BODY = "body".getBytes(StandardCharsets.UTF_8);

    private S3Service s3Service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, true);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.setBucketObjectLockEnabled(BUCKET);
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putObject(BUCKET, "source.txt", BODY, "text/plain", null);
    }

    @Test
    void simpleDeleteOverLegalHoldCreatesDeleteMarker() {
        S3Object held = putLegalHold("held.txt");

        S3Object marker = s3Service.deleteObject(BUCKET, "held.txt", null, false);

        assertTrue(marker.isDeleteMarker());
        assertLockedVersionSurvives("held.txt", held.getVersionId());
    }

    @Test
    void simpleDeleteOverGovernanceRetentionCreatesDeleteMarkerWithoutBypass() {
        S3Object retained = putRetention("governed.txt", "GOVERNANCE");

        S3Object marker = s3Service.deleteObject(BUCKET, "governed.txt", null, false);

        assertTrue(marker.isDeleteMarker());
        assertLockedVersionSurvives("governed.txt", retained.getVersionId());
    }

    @Test
    void simpleDeleteOverComplianceRetentionCreatesDeleteMarker() {
        S3Object retained = putRetention("compliance.txt", "COMPLIANCE");

        S3Object marker = s3Service.deleteObject(BUCKET, "compliance.txt", null, false);

        assertTrue(marker.isDeleteMarker());
        assertLockedVersionSurvives("compliance.txt", retained.getVersionId());
    }

    @Test
    void putOverLockedVersionCreatesNewVersion() {
        S3Object held = putLegalHold("held.txt");
        S3Object retained = putRetention("governed.txt", "GOVERNANCE");

        S3Object heldV2 = s3Service.putObject(BUCKET, "held.txt", BODY, "text/plain", null);
        S3Object retainedV2 = s3Service.putObject(BUCKET, "governed.txt", BODY, "text/plain", null);

        assertNotEquals(held.getVersionId(), heldV2.getVersionId());
        assertNotEquals(retained.getVersionId(), retainedV2.getVersionId());
        assertLockedVersionSurvives("held.txt", held.getVersionId());
        assertLockedVersionSurvives("governed.txt", retained.getVersionId());
    }

    @Test
    void copyOverLockedVersionCreatesNewVersion() {
        S3Object held = putLegalHold("held.txt");

        S3Object copy = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "held.txt");

        assertNotEquals(held.getVersionId(), copy.getVersionId());
        assertLockedVersionSurvives("held.txt", held.getVersionId());
    }

    @Test
    void completeMultipartUploadOverLockedVersionCreatesNewVersion() {
        S3Object held = putLegalHold("held.txt");

        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "held.txt", null);
        s3Service.uploadPart(BUCKET, "held.txt", upload.getUploadId(), 1, BODY);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "held.txt", upload.getUploadId(),
                List.of(1), null, null);

        assertNotEquals(held.getVersionId(), completed.getVersionId());
        assertLockedVersionSurvives("held.txt", held.getVersionId());
    }

    @Test
    void permanentDeleteOfLockedVersionIsStillDenied() {
        S3Object held = putLegalHold("held.txt");
        S3Object governed = putRetention("governed.txt", "GOVERNANCE");
        S3Object compliance = putRetention("compliance.txt", "COMPLIANCE");
        s3Service.deleteObject(BUCKET, "held.txt", null, false);
        s3Service.deleteObject(BUCKET, "governed.txt", null, false);
        s3Service.deleteObject(BUCKET, "compliance.txt", null, false);

        assertAccessDenied(() -> s3Service.deleteObject(BUCKET, "held.txt", held.getVersionId(), true));
        assertAccessDenied(() -> s3Service.deleteObject(BUCKET, "governed.txt", governed.getVersionId(), false));
        assertAccessDenied(() -> s3Service.deleteObject(BUCKET, "compliance.txt", compliance.getVersionId(), true));
    }

    @Test
    void writesOverLockedPreVersioningObjectAreStillDenied() {
        s3Service.createBucket("pre-versioning-bucket", "us-east-1");
        s3Service.setBucketObjectLockEnabled("pre-versioning-bucket");
        s3Service.putObject("pre-versioning-bucket", "held.txt", BODY, "text/plain", null,
                new PutObjectOptions().withLegalHoldStatus("ON"));
        s3Service.putBucketVersioning("pre-versioning-bucket", "Enabled");

        assertAccessDenied(() -> s3Service.putObject("pre-versioning-bucket", "held.txt", BODY, "text/plain", null));
        assertAccessDenied(() -> s3Service.copyObject(BUCKET, "source.txt", "pre-versioning-bucket", "held.txt"));
        assertAccessDenied(() -> s3Service.deleteObject("pre-versioning-bucket", "held.txt", null, false));
        assertEquals("ON", s3Service.getObject("pre-versioning-bucket", "held.txt").getLegalHoldStatus());
    }

    private S3Object putLegalHold(String key) {
        return s3Service.putObject(BUCKET, key, BODY, "text/plain", null,
                new PutObjectOptions().withLegalHoldStatus("ON"));
    }

    private S3Object putRetention(String key, String mode) {
        return s3Service.putObject(BUCKET, key, BODY, "text/plain", null,
                new PutObjectOptions()
                        .withObjectLockMode(mode)
                        .withRetainUntilDate(Instant.now().plusSeconds(3600)));
    }

    private void assertLockedVersionSurvives(String key, String versionId) {
        S3Object version = s3Service.getObject(BUCKET, key, versionId);
        assertNotNull(version);
        assertTrue("ON".equals(version.getLegalHoldStatus()) || version.getObjectLockMode() != null);
    }

    private static void assertAccessDenied(Runnable action) {
        AwsException exception = assertThrows(AwsException.class, action::run);
        assertEquals("AccessDenied", exception.getErrorCode());
    }
}
