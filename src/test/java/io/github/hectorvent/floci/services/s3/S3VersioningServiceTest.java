package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class S3VersioningServiceTest {

    private S3Service s3Service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, true);
        s3Service.createBucket("versioned-bucket", "us-east-1");
    }

    @Test
    void enableVersioning() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        assertEquals("Enabled", s3Service.getBucketVersioning("versioned-bucket"));
    }

    @Test
    void suspendVersioning() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");
        assertEquals("Suspended", s3Service.getBucketVersioning("versioned-bucket"));
    }

    @Test
    void versioningCannotBeSuspendedOnABucketWithObjectLock() {
        s3Service.enableObjectLockOnCreate("versioned-bucket");

        AwsException refused = assertThrows(AwsException.class,
                () -> s3Service.putBucketVersioning("versioned-bucket", "Suspended"));
        assertEquals("InvalidBucketState", refused.getErrorCode());
        assertEquals(409, refused.getHttpStatus());
        assertEquals("An Object Lock configuration is present on this bucket, so the versioning state "
                + "cannot be changed.", refused.getMessage());
        assertEquals("Enabled", s3Service.getBucketVersioning("versioned-bucket"));

        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        assertEquals("Enabled", s3Service.getBucketVersioning("versioned-bucket"));
    }

    @Test
    void objectLockConfigurationNeedsVersioningEnabled() {
        assertObjectLockConfigurationRefused();
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");
        assertObjectLockConfigurationRefused();

        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObjectLockConfiguration("versioned-bucket", "GOVERNANCE", "Days", 1);
        assertEquals("GOVERNANCE", s3Service.getObjectLockConfiguration("versioned-bucket").mode());
    }

    @Test
    void aMissingBucketAndAnUnknownStatusAreReportedBeforeObjectLock() {
        assertEquals("NoSuchBucket", assertThrows(AwsException.class,
                () -> s3Service.putBucketVersioning("missing-bucket", "Bogus")).getErrorCode());
        assertEquals("NoSuchBucket", assertThrows(AwsException.class,
                () -> s3Service.putObjectLockConfiguration("missing-bucket", null, null, 0)).getErrorCode());
        s3Service.enableObjectLockOnCreate("versioned-bucket");
        assertEquals("MalformedXML", assertThrows(AwsException.class,
                () -> s3Service.putBucketVersioning("versioned-bucket", "Bogus")).getErrorCode());
    }

    private void assertObjectLockConfigurationRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> s3Service.putObjectLockConfiguration("versioned-bucket", "GOVERNANCE", "Days", 1));
        assertEquals("InvalidBucketState", refused.getErrorCode());
        assertEquals(409, refused.getHttpStatus());
        assertEquals("Versioning must be 'Enabled' on the bucket to apply a Object Lock configuration",
                refused.getMessage());
        assertEquals("ObjectLockConfigurationNotFoundError", assertThrows(AwsException.class,
                () -> s3Service.getObjectLockConfiguration("versioned-bucket")).getErrorCode());
    }

    @Test
    void suspendedPutReplacesAnArchivedNullVersionWithoutLeavingADuplicate() {
        s3Service.putObject("versioned-bucket", "key",
                "original null".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "versioned".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");

        S3Object replacement = s3Service.putObject("versioned-bucket", "key",
                "replacement null".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        List<S3Object> versions = s3Service.listObjectVersions("versioned-bucket", "key", 100, null).versions();
        assertEquals(1, versions.stream().filter(version -> version.getVersionId() == null).count());
        S3Object listedNull = versions.stream().filter(version -> version.getVersionId() == null).findFirst().orElseThrow();
        assertTrue(listedNull.isLatest());
        assertEquals(replacement.getETag(), listedNull.getETag());
        assertEquals("replacement null", new String(
                s3Service.getObject("versioned-bucket", "key", "null").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void suspendedDeleteRemovesAnArchivedNullVersion() {
        s3Service.putObject("versioned-bucket", "key",
                "original null".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "versioned".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");

        s3Service.deleteObject("versioned-bucket", "key");

        AwsException missing = assertThrows(AwsException.class,
                () -> s3Service.getObject("versioned-bucket", "key", "null"));
        assertEquals("NoSuchVersion", missing.getErrorCode());
    }

    @Test
    void deletingAnAbsentNullVersionDoesNotDeleteAKeyThatLooksLikeItsHistoryEntry() {
        s3Service.putObject("versioned-bucket", "key#v#null",
                "tricky".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        assertNull(s3Service.deleteObject("versioned-bucket", "key", "null"));
        assertEquals("tricky", new String(
                s3Service.getObject("versioned-bucket", "key#v#null").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void archivingANullVersionDoesNotOverwriteAKeyThatLooksLikeItsLegacyHistoryEntry() {
        s3Service.putObject("versioned-bucket", "key",
                "null version".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObject("versioned-bucket", "key#v#null",
                "tricky".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        S3Object replacement = s3Service.putObject("versioned-bucket", "key",
                "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        assertEquals("tricky", new String(
                s3Service.getObject("versioned-bucket", "key#v#null").getData(), StandardCharsets.UTF_8));
        assertEquals("null version", new String(
                s3Service.getObject("versioned-bucket", "key", "null").getData(), StandardCharsets.UTF_8));

        s3Service.deleteObject("versioned-bucket", "key", replacement.getVersionId());

        assertEquals("null version", new String(
                s3Service.getObject("versioned-bucket", "key").getData(), StandardCharsets.UTF_8));
        assertEquals("null version", new String(
                s3Service.getObject("versioned-bucket", "key", "null").getData(), StandardCharsets.UTF_8));
        assertEquals("tricky", new String(
                s3Service.getObject("versioned-bucket", "key#v#null").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void archivedNullVersionIsNotListedAsAnOrdinaryObject() {
        s3Service.putObject("versioned-bucket", "key",
                "null version".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        List<S3Object> objects = s3Service.listObjects("versioned-bucket", null, null, 100);

        assertEquals(1, objects.size());
        assertEquals("key", objects.get(0).getKey());
        assertNotNull(objects.get(0).getVersionId());
    }

    @Test
    void archivedNullVersionWithLegacyMarkerInKeyIsListedOnce() {
        String key = "key#v#part";
        s3Service.putObject("versioned-bucket", key,
                "null version".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object current = s3Service.putObject("versioned-bucket", key,
                "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        List<S3Object> versions = s3Service.listObjectVersions(
                "versioned-bucket", key, 100, null).versions();

        assertEquals(1, versions.stream().filter(version -> version.getVersionId() == null).count());

        s3Service.deleteObject("versioned-bucket", key, current.getVersionId());

        versions = s3Service.listObjectVersions("versioned-bucket", key, 100, null).versions();
        assertEquals(1, versions.size());
        assertNull(versions.getFirst().getVersionId());
        assertTrue(versions.getFirst().isLatest());
    }

    @Test
    void staleMetadataUpdateDoesNotOverwriteAReplacementArchivedNullVersion() {
        MetadataReplacementStore objectStore = new MetadataReplacementStore();
        S3Service service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir, true);
        service.createBucket("versioned-bucket", "us-east-1");
        service.putObject("versioned-bucket", "key",
                "old".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        service.putBucketVersioning("versioned-bucket", "Enabled");
        service.putObject("versioned-bucket", "key",
                "numbered".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        objectStore.arm(service);

        service.putObjectTagging("versioned-bucket", "key", "null", Map.of("stale", "tag"));

        S3Object nullVersion = service.getObject("versioned-bucket", "key", "null");
        assertEquals("replacement", new String(nullVersion.getData(), StandardCharsets.UTF_8));
        assertEquals("replacement".length(), nullVersion.getSize());
        assertTrue(service.getObjectTagging("versioned-bucket", "key", "null").isEmpty());
    }

    @Test
    void currentNullSnapshotSurvivesArchiveAndPromotionDuringTheRead() {
        SnapshotAbaStore objectStore = new SnapshotAbaStore();
        S3Service service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir, true);
        service.createBucket("versioned-bucket", "us-east-1");
        service.putObject("versioned-bucket", "key",
                "original".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        service.putBucketVersioning("versioned-bucket", "Enabled");
        objectStore.arm(service);

        S3Object nullVersion = service.getObject("versioned-bucket", "key", "null");

        assertEquals("original", new String(nullVersion.getData(), StandardCharsets.UTF_8));
        assertEquals("original", new String(
                service.getObject("versioned-bucket", "key").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void latestNullSnapshotSurvivesArchiveAndPromotionDuringTheRead() {
        SnapshotAbaStore objectStore = new SnapshotAbaStore();
        S3Service service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir, true);
        service.createBucket("versioned-bucket", "us-east-1");
        service.putObject("versioned-bucket", "key",
                "original".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        service.putBucketVersioning("versioned-bucket", "Enabled");
        objectStore.arm(service);

        S3Object latest = service.getObject("versioned-bucket", "key");

        assertEquals("original", new String(latest.getData(), StandardCharsets.UTF_8));
        assertEquals("original", new String(
                service.getObject("versioned-bucket", "key").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void delayedMetadataUpdateDemotesTheNullHistoryEntryItTargets() {
        MetadataArchiveStore objectStore = new MetadataArchiveStore();
        S3Service service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir, true);
        service.createBucket("versioned-bucket", "us-east-1");
        service.putObject("versioned-bucket", "key",
                "original".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        service.putBucketVersioning("versioned-bucket", "Enabled");
        objectStore.arm(service);

        service.putObjectTagging("versioned-bucket", "key", "null", Map.of("kept", "tag"));

        List<S3Object> versions = service.listObjectVersions("versioned-bucket", "key", 100, null).versions();
        assertEquals(1, versions.stream().filter(S3Object::isLatest).count());
        assertFalse(versions.stream()
                .filter(version -> version.getVersionId() == null)
                .findFirst()
                .orElseThrow()
                .isLatest());
        assertEquals(Map.of("kept", "tag"), service.getObjectTagging("versioned-bucket", "key", "null"));
    }

    @Test
    void archivedNullKeepsItsSequencerForSameMillisecondPromotionOrder() {
        InMemoryStorage<String, S3Object> objectStore = new InMemoryStorage<>();
        S3Service service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir, true);
        service.createBucket("versioned-bucket", "us-east-1");
        service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object older = service.putObject("versioned-bucket", "key",
                "older numbered".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        service.putBucketVersioning("versioned-bucket", "Suspended");
        service.putObject("versioned-bucket", "key",
                "newer null".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        Instant tied = Instant.parse("2026-10-21T00:00:00Z");
        objectStore.get("versioned-bucket/key#v#" + older.getVersionId()).orElseThrow().setLastModified(tied);
        objectStore.get("versioned-bucket/key").orElseThrow().setLastModified(tied);
        service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object current = service.putObject("versioned-bucket", "key",
                "current".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        service.deleteObject("versioned-bucket", "key", current.getVersionId());

        assertEquals("newer null", new String(
                service.getObject("versioned-bucket", "key").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void versioningNotEnabledByDefault() {
        assertNull(s3Service.getBucketVersioning("versioned-bucket"));
    }

    @Test
    void invalidVersioningStatus() {
        assertThrows(AwsException.class, () ->
                s3Service.putBucketVersioning("versioned-bucket", "Invalid"));
    }

    @Test
    void putObjectWithVersioning() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object obj = s3Service.putObject("versioned-bucket", "test.txt",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        assertNotNull(obj.getVersionId());
    }

    @Test
    void putObjectWithoutVersioningHasNoVersionId() {
        S3Object obj = s3Service.putObject("versioned-bucket", "test.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        assertNull(obj.getVersionId());
    }

    @Test
    void deletePreVersioningObjectByNullVersionIdRemovesObjectPermanently() {
        s3Service.putObject("versioned-bucket", "pre-version.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        assertEquals("data", new String(s3Service.getObject("versioned-bucket", "pre-version.txt", "null").getData(),
                StandardCharsets.UTF_8));
        assertNotNull(s3Service.headObject("versioned-bucket", "pre-version.txt", "null"));

        s3Service.deleteObject("versioned-bucket", "pre-version.txt", "null");

        assertThrows(AwsException.class,
                () -> s3Service.getObject("versioned-bucket", "pre-version.txt"));
        assertDoesNotThrow(() -> s3Service.deleteBucket("versioned-bucket"));
    }

    @Test
    void multipleVersionsOfSameKey() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        S3Object v1 = s3Service.putObject("versioned-bucket", "test.txt",
                "version1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        S3Object v2 = s3Service.putObject("versioned-bucket", "test.txt",
                "version2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        assertNotEquals(v1.getVersionId(), v2.getVersionId());

        // Get latest should return v2
        S3Object latest = s3Service.getObject("versioned-bucket", "test.txt");
        assertEquals("version2", new String(latest.getData()));

        // Get specific version should return v1
        S3Object specific = s3Service.getObject("versioned-bucket", "test.txt", v1.getVersionId());
        assertEquals("version1", new String(specific.getData()));
    }

    @Test
    void deleteCreatesMarkerWhenVersioned() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "test.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        S3Object result = s3Service.deleteObject("versioned-bucket", "test.txt");
        assertNotNull(result);
        assertTrue(result.isDeleteMarker());

        // Get should now fail with NoSuchKey
        assertThrows(AwsException.class, () ->
                s3Service.getObject("versioned-bucket", "test.txt"));
    }

    @Test
    void deleteWithVersionIdIsPermanent() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "test.txt",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        s3Service.deleteObject("versioned-bucket", "test.txt", v1.getVersionId());

        // The specific version should be gone
        assertThrows(AwsException.class, () ->
                s3Service.getObject("versioned-bucket", "test.txt", v1.getVersionId()));
    }

    @Test
    void getObjectAfterDeleteMarkerWithSpecificVersion() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "test.txt",
                "v1-data".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        // Delete creates marker
        s3Service.deleteObject("versioned-bucket", "test.txt");

        // Latest is gone
        assertThrows(AwsException.class, () ->
                s3Service.getObject("versioned-bucket", "test.txt"));

        // But specific version still accessible
        S3Object retrieved = s3Service.getObject("versioned-bucket", "test.txt", v1.getVersionId());
        assertEquals("v1-data", new String(retrieved.getData()));
    }

    @Test
    void listObjectVersions() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "test.txt",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObject("versioned-bucket", "test.txt",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        S3Service.ListVersionsResult result = s3Service.listObjectVersions("versioned-bucket", null, 100, null);
        assertEquals(2, result.versions().size());
        assertFalse(result.isTruncated());
    }

    @Test
    void listObjectVersionsIncludesDeleteMarkers() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "test.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "test.txt");

        S3Service.ListVersionsResult result = s3Service.listObjectVersions("versioned-bucket", null, 100, null);
        assertEquals(2, result.versions().size());
        assertTrue(result.versions().stream().anyMatch(S3Object::isDeleteMarker));
    }

    @Test
    void deleteBucketRejectsDeleteMarkerOnlyUntilPurged() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object version = s3Service.putObject("versioned-bucket", "key",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "key");
        s3Service.deleteObject("versioned-bucket", "key", version.getVersionId());

        assertEquals(1, s3Service.listObjectVersions("versioned-bucket", null, 100, null).versions().size());
        assertBucketNotEmptyThenPurgeAndDelete();
    }

    @Test
    void deleteBucketRejectsSuspendedBucketWithOlderVersionUntilPurged() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "key");
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");

        assertBucketNotEmptyThenPurgeAndDelete();
    }

    @Test
    void purgedBucketCanBeRecreatedWithoutResurrectingVersions() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "key");

        assertBucketNotEmptyThenPurgeAndDelete();
        s3Service.createBucket("versioned-bucket", "us-east-1");
        assertTrue(s3Service.listObjectVersions("versioned-bucket", null, 100, null).versions().isEmpty());
    }

    private void assertBucketNotEmptyThenPurgeAndDelete() {
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.deleteBucket("versioned-bucket"));
        assertEquals("BucketNotEmpty", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        List<S3Object> versions = s3Service.listObjectVersions("versioned-bucket", null, 100, null).versions();
        assertFalse(versions.isEmpty());
        for (S3Object version : versions) {
            s3Service.deleteObject("versioned-bucket", version.getKey(), version.getVersionId());
        }
        assertTrue(s3Service.listObjectVersions("versioned-bucket", null, 100, null).versions().isEmpty());
        assertDoesNotThrow(() -> s3Service.deleteBucket("versioned-bucket"));
    }

    @Test
    void getObjectWithNonExistentVersionIdThrowsNoSuchVersion() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "test.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        AwsException ex = assertThrows(AwsException.class, () ->
                s3Service.getObject("versioned-bucket", "test.txt", "fake-version-id"));
        assertEquals("NoSuchVersion", ex.getErrorCode());
    }

    @Test
    void copyObjectFromOlderVersionRestoresThatContentAsLatest() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "key",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObject("versioned-bucket", "key",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        assertEquals("v2", new String(s3Service.getObject("versioned-bucket", "key").getData()));

        s3Service.copyObject("versioned-bucket", "key", "versioned-bucket", "key",
                v1.getVersionId(), new CopyObjectOptions());

        assertEquals("v1", new String(s3Service.getObject("versioned-bucket", "key").getData()));
    }

    @Test
    void versionedFileUsesS3dataSuffixOnDisk() {
        S3Service diskService = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, false);
        diskService.createBucket("versioned-bucket", "us-east-1");
        diskService.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = diskService.putObject("versioned-bucket", "test.txt",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        Path versionedPath = tempDir.resolve(".accounts")
                .resolve("000000000000")
                .resolve(".versions")
                .resolve("versioned-bucket")
                .resolve("test.txt")
                .resolve(v1.getVersionId() + ".s3data");
        assertTrue(Files.exists(versionedPath),
                "versioned file should be stored with .s3data suffix");
    }

    @Test
    void openObjectStreamReadsSpecificVersionFromDisk() throws Exception {
        S3Service diskService = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, false);
        diskService.createBucket("versioned-bucket", "us-east-1");
        diskService.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = diskService.putObject("versioned-bucket", "stream.txt",
                "first-version".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        diskService.putObject("versioned-bucket", "stream.txt",
                "second-version".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        try (InputStream stream = diskService.openObjectStream(
                "versioned-bucket", "stream.txt", v1.getVersionId())) {
            assertEquals("first-version", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void deleteMarkerByVersionIdRestoresPreviousVersion() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "file",
                "original".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        S3Object marker = s3Service.deleteObject("versioned-bucket", "file");
        assertThrows(AwsException.class, () -> s3Service.getObject("versioned-bucket", "file"));

        s3Service.deleteObject("versioned-bucket", "file", marker.getVersionId());

        S3Object restored = s3Service.getObject("versioned-bucket", "file");
        assertEquals("original", new String(restored.getData()));
        assertEquals(v1.getVersionId(), restored.getVersionId());
    }

    @Test
    void deleteOnlyVersionByVersionIdClearsLatestPointer() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "sole",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        s3Service.deleteObject("versioned-bucket", "sole", v1.getVersionId());

        assertThrows(AwsException.class, () -> s3Service.getObject("versioned-bucket", "sole"));
    }

    @Test
    void deleteNonLatestVersionByVersionIdLeavesLatestUnchanged() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "multi",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObject("versioned-bucket", "multi",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        s3Service.deleteObject("versioned-bucket", "multi", v1.getVersionId());

        S3Object latest = s3Service.getObject("versioned-bucket", "multi");
        assertEquals("v2", new String(latest.getData()));
    }

    @Test
    void listObjectsExcludesDeleteMarkers() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "keep.txt",
                "keep".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObject("versioned-bucket", "delete-me.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "delete-me.txt");

        List<S3Object> objects = s3Service.listObjects("versioned-bucket", null, null, 100);
        assertEquals(1, objects.size());
        assertEquals("keep.txt", objects.get(0).getKey());
    }

    @Test
    void taggingAVersionChangesOnlyThatVersion() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        S3Object v1 = s3Service.putObject("versioned-bucket", "key",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        S3Object v2 = s3Service.putObject("versioned-bucket", "key",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        assertEquals(v2.getVersionId(),
                s3Service.putObjectTagging("versioned-bucket", "key", null, Map.of("version", "current")));

        assertEquals(v1.getVersionId(),
                s3Service.putObjectTagging("versioned-bucket", "key", v1.getVersionId(), Map.of("version", "first")));
        assertEquals(new S3Service.ObjectTaggingResult(v1.getVersionId(), Map.of("version", "first")),
                s3Service.getObjectTaggingResult("versioned-bucket", "key", v1.getVersionId()));
        assertEquals(new S3Service.ObjectTaggingResult(v2.getVersionId(), Map.of("version", "current")),
                s3Service.getObjectTaggingResult("versioned-bucket", "key", null));

        assertEquals(v1.getVersionId(), s3Service.deleteObjectTagging("versioned-bucket", "key", v1.getVersionId()));
        assertEquals(Map.of(), s3Service.getObjectTaggingResult("versioned-bucket", "key", v1.getVersionId()).tags());
        assertEquals(Map.of("version", "current"), s3Service.getObjectTagging("versioned-bucket", "key"));
    }

    @Test
    void versionIdNullNamesTheNullVersionAndNoOther() {
        // An object written before versioning is the null version.
        s3Service.putObject("versioned-bucket", "old", "v0".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        assertNull(s3Service.putObjectTagging("versioned-bucket", "old", "null", Map.of("version", "null")));
        assertEquals(new S3Service.ObjectTaggingResult(null, Map.of("version", "null")),
                s3Service.getObjectTaggingResult("versioned-bucket", "old", "null"));

        // A key written only with versioning on has no null version, and its current version is
        // not one: a request naming the null version fails and leaves the current one alone.
        s3Service.putObject("versioned-bucket", "new", "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObjectTagging("versioned-bucket", "new", Map.of("version", "current"));
        for (Runnable request : List.<Runnable>of(
                () -> s3Service.getObjectTaggingResult("versioned-bucket", "new", "null"),
                () -> s3Service.putObjectTagging("versioned-bucket", "new", "null", Map.of("version", "other")),
                () -> s3Service.deleteObjectTagging("versioned-bucket", "new", "null"),
                () -> s3Service.getObjectTagging("versioned-bucket", "new", "null"))) {
            AwsException e = assertThrows(AwsException.class, request::run);
            assertEquals("NoSuchVersion", e.getErrorCode());
        }
        assertEquals(Map.of("version", "current"), s3Service.getObjectTagging("versioned-bucket", "new"));

        // An object written while versioning is suspended is the null version too, above the
        // versions written before it.
        s3Service.putBucketVersioning("versioned-bucket", "Suspended");
        s3Service.putObject("versioned-bucket", "new", "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        assertNull(s3Service.deleteObjectTagging("versioned-bucket", "new", "null"));
        s3Service.putObjectTagging("versioned-bucket", "new", "null", Map.of("version", "suspended"));
        assertEquals(Map.of("version", "suspended"), s3Service.getObjectTagging("versioned-bucket", "new"));
    }

    @Test
    void taggingADeleteMarkerIsNotAllowed() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key", "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        String markerId = s3Service.deleteObject("versioned-bucket", "key").getVersionId();

        // The marker is refused whether the request names it or it is the current version.
        for (String versionId : Arrays.asList(null, markerId)) {
            Map<String, Runnable> requests = Map.of(
                    "GET", () -> s3Service.getObjectTaggingResult("versioned-bucket", "key", versionId),
                    "PUT", () -> s3Service.putObjectTagging("versioned-bucket", "key", versionId, Map.of("k", "v")),
                    "DELETE", () -> s3Service.deleteObjectTagging("versioned-bucket", "key", versionId));
            requests.forEach((method, request) -> {
                AwsException e = assertThrows(AwsException.class, request::run);
                assertEquals("MethodNotAllowed", e.getErrorCode());
                assertEquals(405, e.getHttpStatus());
                assertEquals(Map.of("Method", method, "ResourceType", "DeleteMarker"), e.getExtendedData());
            });
        }
    }

    @Test
    void copyObjectPreservesSourceTagsByDefault() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "key",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        s3Service.putObjectTagging("versioned-bucket", "key", Map.of("version", "v1-tag"));

        S3Object v2 = s3Service.putObject("versioned-bucket", "key",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        // Copy v2 to itself without specifying TaggingDirective — source tags should be preserved
        // First tag v2
        s3Service.putObjectTagging("versioned-bucket", "key", Map.of("version", "v2-tag"));

        s3Service.copyObject("versioned-bucket", "key", "versioned-bucket", "key",
                v2.getVersionId(), new CopyObjectOptions());

        Map<String, String> tags = s3Service.getObjectTagging("versioned-bucket", "key");
        assertEquals("v2-tag", tags.get("version"),
                "copyObject without TaggingDirective should copy source tags to destination");
    }

    @Test
    void copyObjectWithReplaceTaggerReplacesSourceTags() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "replace-key",
                "content".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObjectTagging("versioned-bucket", "replace-key", Map.of("original", "tag"));

        s3Service.copyObject("versioned-bucket", "replace-key", "versioned-bucket", "replace-key",
                null,
                new CopyObjectOptions()
                        .withTaggingDirective("REPLACE")
                        .withReplacementTagging(Map.of("new", "value")));

        Map<String, String> tags = s3Service.getObjectTagging("versioned-bucket", "replace-key");
        assertEquals("value", tags.get("new"), "REPLACE should apply replacement tags");
        assertNull(tags.get("original"), "REPLACE should not preserve source tags");
    }

    @Test
    void copyObjectWithReplaceTaggerAndNoTagsClearsTags() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "clear-key",
                "content".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putObjectTagging("versioned-bucket", "clear-key", Map.of("should", "disappear"));

        s3Service.copyObject("versioned-bucket", "clear-key", "versioned-bucket", "clear-key",
                null,
                new CopyObjectOptions()
                        .withTaggingDirective("REPLACE")
                        .withReplacementTagging(Map.of()));

        Map<String, String> tags = s3Service.getObjectTagging("versioned-bucket", "clear-key");
        assertTrue(tags.isEmpty(), "REPLACE with empty tags should clear all source tags");
    }

    @Test
    void getObjectReturnsPromotedContentAfterLatestVersionDeleted() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        s3Service.putObject("versioned-bucket", "key",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        S3Object v2 = s3Service.putObject("versioned-bucket", "key",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        // Delete the latest version (v2) — v1 should be promoted
        s3Service.deleteObject("versioned-bucket", "key", v2.getVersionId());

        // getObject without versionId should return v1's content, not v2's
        S3Object result = s3Service.getObject("versioned-bucket", "key");
        assertEquals("v1", new String(result.getData(), StandardCharsets.UTF_8),
                "getObject should return the promoted version's content after deleting the latest");
    }

    @Test
    void deleteObjectsWithExplicitVersionIdsPermanentlyDeletesVersions() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        S3Object v1 = s3Service.putObject("versioned-bucket", "key",
                "v1".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        S3Object v2 = s3Service.putObject("versioned-bucket", "key",
                "v2".getBytes(StandardCharsets.UTF_8), "text/plain", null);

        s3Service.deleteObjects("versioned-bucket", List.of(
                new XmlParser.KeyVersion("key", v1.getVersionId()),
                new XmlParser.KeyVersion("key", v2.getVersionId())));

        S3Service.ListVersionsResult result = s3Service.listObjectVersions("versioned-bucket", null, 100, null);
        assertTrue(result.versions().isEmpty(),
                "batch delete with explicit VersionIds should permanently remove those versions, not place a delete marker");
    }

    @Test
    void deleteObjectsWithNullVersionIdPermanentlyDeletesPreVersioningObject() {
        s3Service.putObject("versioned-bucket", "key",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");

        s3Service.deleteObjects("versioned-bucket", List.of(
                new XmlParser.KeyVersion("key", "null")));

        assertThrows(AwsException.class, () -> s3Service.getObject("versioned-bucket", "key"));
        assertDoesNotThrow(() -> s3Service.deleteBucket("versioned-bucket"));
    }

    private static final class MetadataReplacementStore extends InMemoryStorage<String, S3Object> {
        private S3Service service;
        private boolean armed;

        private void arm(S3Service service) {
            this.service = service;
            armed = true;
        }

        @Override
        public Optional<S3Object> get(String key) {
            Optional<S3Object> result = super.get(key);
            if (armed && key.indexOf('\0') >= 0) {
                armed = false;
                service.putBucketVersioning("versioned-bucket", "Suspended");
                service.putObject("versioned-bucket", "key",
                        "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null);
                service.putBucketVersioning("versioned-bucket", "Enabled");
                service.putObject("versioned-bucket", "key",
                        "latest".getBytes(StandardCharsets.UTF_8), "text/plain", null);
            }
            return result;
        }
    }

    private static final class MetadataArchiveStore extends InMemoryStorage<String, S3Object> {
        private S3Service service;
        private boolean armed;

        private void arm(S3Service service) {
            this.service = service;
            armed = true;
        }

        @Override
        public Optional<S3Object> get(String key) {
            Optional<S3Object> result = super.get(key);
            if (armed && "versioned-bucket/key".equals(key)) {
                armed = false;
                service.putObject("versioned-bucket", "key",
                        "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null);
            }
            return result;
        }
    }

    private static final class SnapshotAbaStore extends InMemoryStorage<String, S3Object> {
        private S3Service service;
        private int stage = -1;
        private String replacementVersionId;

        private void arm(S3Service service) {
            this.service = service;
            stage = 0;
        }

        @Override
        public Optional<S3Object> get(String key) {
            if (!"versioned-bucket/key".equals(key) || stage < 0 || stage > 1) {
                return super.get(key);
            }
            if (stage == 0) {
                Optional<S3Object> original = super.get(key);
                stage = -1;
                replacementVersionId = service.putObject("versioned-bucket", "key",
                        "replacement".getBytes(StandardCharsets.UTF_8), "text/plain", null).getVersionId();
                stage = 1;
                return original;
            }
            stage = -1;
            service.deleteObject("versioned-bucket", "key", replacementVersionId);
            stage = 2;
            return super.get(key);
        }
    }

}
