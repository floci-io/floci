package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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

        List<S3Object> versions = s3Service.listObjectVersions("versioned-bucket", null, 100);
        assertEquals(2, versions.size());
    }

    @Test
    void listObjectVersionsIncludesDeleteMarkers() {
        s3Service.putBucketVersioning("versioned-bucket", "Enabled");
        s3Service.putObject("versioned-bucket", "test.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        s3Service.deleteObject("versioned-bucket", "test.txt");

        List<S3Object> versions = s3Service.listObjectVersions("versioned-bucket", null, 100);
        assertEquals(2, versions.size());
        assertTrue(versions.stream().anyMatch(S3Object::isDeleteMarker));
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
}
