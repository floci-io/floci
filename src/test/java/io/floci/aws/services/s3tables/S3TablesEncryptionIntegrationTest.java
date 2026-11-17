package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.EncryptionConfiguration;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3TablesEncryptionIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void sdkReadsDefaultAndConfiguredBucketEncryption(String region, String partition) {
        try (S3TablesClient client = client(region, "111111111111")) {
            assertReadback(client, null, EncryptionConfiguration.builder().sseAlgorithm("AES256").build());
            EncryptionConfiguration aes = EncryptionConfiguration.builder().sseAlgorithm("AES256").build();
            assertReadback(client, aes, aes);
            EncryptionConfiguration kms = EncryptionConfiguration.builder().sseAlgorithm("aws:kms")
                    .kmsKeyArn("arn:" + partition + ":kms:" + region + ":111111111111:key/"
                            + UUID.randomUUID()).build();
            assertReadback(client, kms, kms);
        }
    }

    @Test
    void readsUseTheRequestedAccountRegionAndExactBucketArn() {
        String name = name();
        try (S3TablesClient first = client("us-east-1", "111111111111");
                S3TablesClient second = client("us-east-1", "222222222222");
                S3TablesClient otherRegion = client("us-west-2", "111111111111")) {
            String firstArn = first.createTableBucket(request -> request.name(name)).arn();
            try {
                String secondArn = second.createTableBucket(request -> request.name(name)
                        .encryptionConfiguration(config -> config.sseAlgorithm("aws:kms")
                                .kmsKeyArn("arn:aws:kms:us-east-1:222222222222:key/" + UUID.randomUUID()))).arn();
                try {
                    assertEquals("AES256", read(first, firstArn).sseAlgorithmAsString());
                    assertEquals("aws:kms", read(second, secondArn).sseAlgorithmAsString());
                    assertThrows(NotFoundException.class, () -> read(first, secondArn));
                    assertThrows(NotFoundException.class, () -> read(second, firstArn));
                    assertThrows(NotFoundException.class, () -> read(otherRegion, firstArn));
                    assertEquals("aws:kms", read(second, secondArn).sseAlgorithmAsString());
                } finally {
                    second.deleteTableBucket(request -> request.tableBucketARN(secondArn));
                }
            } finally {
                first.deleteTableBucket(request -> request.tableBucketARN(firstArn));
            }
        }
    }

    @Test
    void missingAndDeletedBucketsUseModeledErrors() {
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

    private void assertReadback(S3TablesClient client, EncryptionConfiguration configured,
                               EncryptionConfiguration expected) {
        String arn = client.createTableBucket(request -> {
            request.name(name());
            if (configured != null) {
                request.encryptionConfiguration(configured);
            }
        }).arn();
        try {
            assertEquals(expected, read(client, arn));
        } finally {
            client.deleteTableBucket(request -> request.tableBucketARN(arn));
        }
    }

    private EncryptionConfiguration read(S3TablesClient client, String arn) {
        return client.getTableBucketEncryption(request -> request.tableBucketARN(arn)).encryptionConfiguration();
    }

    private String name() {
        return "encryption-read-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private S3TablesClient client(String region, String account) {
        return AwsTestClients.create(S3TablesClient.builder(), account, Region.of(region), endpoint);
    }
}
