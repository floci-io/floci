package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfront.model.UpdateKeyValueStoreResponse;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Native SDK XML/header handling and stale-version refusal; no data-plane or function execution claim. */
@QuarkusTest
class CloudFrontKeyValueStoreEtagSdkIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateAndDeleteDistinguishStaleVersionsFromMalformedHeaders(boolean delete) {
        try (CloudFrontClient client = AwsTestClients.create(
                CloudFrontClient.builder(), "000000000000", Region.US_EAST_1)) {
            String name = "etag-" + UUID.randomUUID().toString().substring(0, 8);
            CreateKeyValueStoreResponse created = client.createKeyValueStore(r -> r.name(name).comment("before"));
            try {
                UpdateKeyValueStoreResponse updated =
                        client.updateKeyValueStore(r -> r.name(name).ifMatch(created.eTag()).comment("after"));
                String current = updated.eTag();
                assertNotEquals(created.eTag(), current);
                if (delete) {
                    assertError("PreconditionFailed", 412,
                            () -> client.deleteKeyValueStore(r -> r.name(name).ifMatch(created.eTag())));
                } else {
                    assertError("PreconditionFailed", 412,
                            () -> client.updateKeyValueStore(r -> r.name(name).ifMatch(created.eTag()).comment("lost")));
                }
                DescribeKeyValueStoreResponse unchanged = client.describeKeyValueStore(r -> r.name(name));
                assertEquals("after", unchanged.keyValueStore().comment());
                assertEquals(current, unchanged.eTag());
                assertEquals(created.keyValueStore().arn(), unchanged.keyValueStore().arn());
                for (String malformed : List.of("stale", "")) {
                    assertError("InvalidIfMatchVersion", 400,
                            () -> client.updateKeyValueStore(r -> r.name(name).ifMatch(malformed).comment("lost")));
                    assertError("InvalidIfMatchVersion", 400,
                            () -> client.deleteKeyValueStore(r -> r.name(name).ifMatch(malformed)));
                }
                DescribeKeyValueStoreResponse retained = client.describeKeyValueStore(r -> r.name(name));
                assertEquals("after", retained.keyValueStore().comment());
                assertEquals(current, retained.eTag());
            } finally {
                String cleanupVersion = client.describeKeyValueStore(r -> r.name(name)).eTag();
                client.deleteKeyValueStore(r -> r.name(name).ifMatch(cleanupVersion));
            }
            assertError("EntityNotFound", 404, () -> client.describeKeyValueStore(r -> r.name(name)));
        }
    }

    private static void assertError(String code, int status, Runnable action) {
        CloudFrontException failure = assertThrows(CloudFrontException.class, action::run);
        assertEquals(code, failure.awsErrorDetails().errorCode());
        assertEquals(status, failure.statusCode());
    }
}
