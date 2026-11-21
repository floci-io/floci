package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;
import software.amazon.awssdk.services.s3tables.model.StorageClassConfiguration;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class S3TablesStorageReadIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void sdkReadsDefaultAndExplicitBucketStorageClasses(String region) {
        try (S3TablesClient client = client(region, "111111111111")) {
            assertReadback(client, null, "STANDARD");
            assertReadback(client, "STANDARD", "STANDARD");
            assertReadback(client, "INTELLIGENT_TIERING", "INTELLIGENT_TIERING");
        }
    }

    @Test
    void readsStayWithinTheRequestedAccountRegionAndExactBucketArn() {
        String name = name();
        try (S3TablesClient first = client("us-east-1", "111111111111");
                S3TablesClient second = client("us-east-1", "222222222222");
                S3TablesClient otherRegion = client("us-west-2", "111111111111")) {
            String firstArn = first.createTableBucket(request -> request.name(name)).arn();
            try {
                String secondArn = second.createTableBucket(request -> request.name(name)
                        .storageClassConfiguration(config -> config.storageClass("INTELLIGENT_TIERING"))).arn();
                try {
                    assertEquals("STANDARD", read(first, firstArn).storageClassAsString());
                    assertEquals("INTELLIGENT_TIERING", read(second, secondArn).storageClassAsString());
                    assertThrows(NotFoundException.class, () -> read(first, secondArn));
                    assertThrows(NotFoundException.class, () -> read(second, firstArn));
                    assertThrows(NotFoundException.class, () -> read(otherRegion, firstArn));
                    assertEquals("INTELLIGENT_TIERING", read(second, secondArn).storageClassAsString());
                } finally {
                    second.deleteTableBucket(request -> request.tableBucketARN(secondArn));
                }
            } finally {
                first.deleteTableBucket(request -> request.tableBucketARN(firstArn));
            }
        }
    }

    @Test
    void missingAndDeletedBucketsReturnTheModeledError() {
        try (S3TablesClient client = client("us-east-1", "test")) {
            String arn = client.createTableBucket(request -> request.name(name())).arn();
            try {
                assertThrows(NotFoundException.class, () -> read(client, arn + "-missing"));
            } finally {
                client.deleteTableBucket(request -> request.tableBucketARN(arn));
            }
            assertThrows(NotFoundException.class, () -> read(client, arn));
        }
    }

    private void assertReadback(S3TablesClient client, String configured, String expected) {
        String arn = client.createTableBucket(request -> {
            request.name(name());
            if (configured != null) {
                request.storageClassConfiguration(config -> config.storageClass(configured));
            }
        }).arn();
        try {
            StorageClassConfiguration configuration = StorageClassConfiguration.builder().storageClass(expected).build();
            assertEquals(configuration, read(client, arn));
        } finally {
            client.deleteTableBucket(request -> request.tableBucketARN(arn));
        }
    }

    private StorageClassConfiguration read(S3TablesClient client, String arn) {
        return client.getTableBucketStorageClass(request -> request.tableBucketARN(arn)).storageClassConfiguration();
    }

    private String name() {
        return "storage-read-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private S3TablesClient client(String region, String account) {
        return AwsTestClients.create(S3TablesClient.builder(), account, Region.of(region), endpoint);
    }
}
