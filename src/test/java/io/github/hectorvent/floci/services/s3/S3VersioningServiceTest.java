package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
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
import java.util.List;
import java.util.Map;

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

        Path versionedPath = tempDir.resolve(".versions")
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

}
