package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.CreateTableBucketResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class S3TablesRouteIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"buckets", "get-table", "namespaces", "tables"})
    void pathStyleS3RequestsForABucketNamedAfterAnS3TablesRouteReachS3(String bucket) {
        try (S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                "000000000000", Region.US_EAST_1)) {
            s3.createBucket(r -> r.bucket(bucket));
            s3.headBucket(r -> r.bucket(bucket));
            s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("hello"));

            assertEquals("hello", s3.getObjectAsBytes(r -> r.bucket(bucket).key("k")).asUtf8String());
            assertEquals(1, s3.listObjectsV2(r -> r.bucket(bucket)).keyCount());
            s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(o -> o.key("k"))));
            assertThrows(NoSuchKeyException.class, () -> s3.getObject(r -> r.bucket(bucket).key("k")));
            s3.deleteBucket(r -> r.bucket(bucket));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"buckets", "tables"})
    void s3TablesRequestsStillReachS3TablesWhileAnS3BucketHasTheSameName(String name) {
        try (S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                "000000000000", Region.US_EAST_1);
                S3TablesClient tables = AwsTestClients.create(S3TablesClient.builder(),
                        "000000000000", Region.US_EAST_1)) {
            s3.createBucket(r -> r.bucket(name));
            CreateTableBucketResponse created = tables.createTableBucket(r -> r.name("route-" + name));
            try {
                assertEquals(created.arn(), tables.getTableBucket(r -> r.tableBucketARN(created.arn())).arn());
                assertTrue(tables.listTableBuckets(r -> { }).tableBuckets().stream()
                        .anyMatch(b -> b.arn().equals(created.arn())));
                s3.headBucket(r -> r.bucket(name));
            } finally {
                tables.deleteTableBucket(r -> r.tableBucketARN(created.arn()));
                s3.deleteBucket(r -> r.bucket(name));
            }
        }
    }
}
