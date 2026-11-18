package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code ListObjectVersions} resumes inside a key even when the version its marker names was deleted. */
@QuarkusTest
class S3ListVersionsDeletedMarkerIntegrationTest {

    @Test
    void deletingEachPageBeforeTheNextOneEmptiesTheBucket() {
        String bucket = "purge-versions-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1)) {
            s3.createBucket(r -> r.bucket(bucket));
            s3.putBucketVersioning(r -> r.bucket(bucket)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            List<String> written = new ArrayList<>();
            for (String key : List.of("k", "k", "k", "z", "z")) {
                written.add(key + "@" + s3.putObject(r -> r.bucket(bucket).key(key),
                        RequestBody.fromString("body")).versionId());
            }

            List<String> purged = new ArrayList<>();
            String keyMarker = null;
            String versionIdMarker = null;
            ListObjectVersionsResponse page;
            do {
                String km = keyMarker;
                String vm = versionIdMarker;
                page = s3.listObjectVersions(r -> r.bucket(bucket).maxKeys(2).keyMarker(km).versionIdMarker(vm));
                for (ObjectVersion version : page.versions()) {
                    s3.deleteObject(r -> r.bucket(bucket).key(version.key()).versionId(version.versionId()));
                    purged.add(version.key() + "@" + version.versionId());
                }
                keyMarker = page.nextKeyMarker();
                versionIdMarker = page.nextVersionIdMarker();
            } while (page.isTruncated());

            assertEquals(written.stream().sorted().toList(), purged.stream().sorted().toList());
            assertEquals(List.of(), s3.listObjectVersions(r -> r.bucket(bucket)).versions());
        }
    }
}
