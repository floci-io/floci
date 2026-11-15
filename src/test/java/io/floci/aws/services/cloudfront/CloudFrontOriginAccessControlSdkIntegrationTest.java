package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateOriginAccessControlResponse;
import software.amazon.awssdk.services.cloudfront.model.GetOriginAccessControlConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.GetOriginAccessControlResponse;
import software.amazon.awssdk.services.cloudfront.model.NoSuchOriginAccessControlException;
import software.amazon.awssdk.services.cloudfront.model.OriginAccessControlConfig;
import software.amazon.awssdk.services.cloudfront.model.UpdateOriginAccessControlResponse;

import java.net.URI;

import static io.floci.aws.services.cloudfront.CloudFrontOriginAccessControlFixtures.configuration;
import static io.floci.aws.services.cloudfront.CloudFrontOriginAccessControlFixtures.deleteIfPresent;
import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses the SDK's canonical /origin-access-control/{Id}/config update route. */
@QuarkusTest
class CloudFrontOriginAccessControlSdkIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void sdkUpdatesTheCompleteConfigurationAndBothGettersReadItBack() {
        try (CloudFrontClient client = client()) {
            OriginAccessControlConfig initial = configuration();
            CreateOriginAccessControlResponse created = client.createOriginAccessControl(
                    request -> request.originAccessControlConfig(initial));
            String id = created.originAccessControl().id();
            try {
                assertReadback(client, id, initial);
                GetOriginAccessControlConfigResponse before =
                        client.getOriginAccessControlConfig(request -> request.id(id));
                OriginAccessControlConfig updated = before.originAccessControlConfig().toBuilder()
                        .description("updated through native SDK").signingBehavior("never").build();
                UpdateOriginAccessControlResponse response = client.updateOriginAccessControl(
                        request -> request.id(id).ifMatch(before.eTag()).originAccessControlConfig(updated));

                assertEquals(200, response.sdkHttpResponse().statusCode());
                assertEquals(id, response.originAccessControl().id());
                assertEquals(updated, response.originAccessControl().originAccessControlConfig());
                assertEquals(response.eTag(), assertReadback(client, id, updated));

                client.deleteOriginAccessControl(request -> request.id(id).ifMatch(response.eTag()));
                assertThrows(NoSuchOriginAccessControlException.class,
                        () -> client.getOriginAccessControl(request -> request.id(id)));
            } finally {
                deleteIfPresent(client, id);
            }
        }
    }

    @Test
    void missingIfMatchReachesTheModeledErrorWithoutChangingTheControl() {
        try (CloudFrontClient client = client()) {
            OriginAccessControlConfig initial = configuration();
            String id = client.createOriginAccessControl(request -> request.originAccessControlConfig(initial))
                    .originAccessControl().id();
            try {
                assertAwsError("InvalidIfMatchVersion", 400,
                        () -> client.updateOriginAccessControl(request -> request.id(id)
                                .originAccessControlConfig(initial.toBuilder().description("refused").build())));
                assertReadback(client, id, initial);
            } finally {
                deleteIfPresent(client, id);
            }
        }
    }

    private CloudFrontClient client() {
        return AwsTestClients.create(CloudFrontClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }

    private static String assertReadback(CloudFrontClient client, String id,
                                         OriginAccessControlConfig expected) {
        GetOriginAccessControlResponse full = client.getOriginAccessControl(request -> request.id(id));
        GetOriginAccessControlConfigResponse config =
                client.getOriginAccessControlConfig(request -> request.id(id));
        assertEquals(id, full.originAccessControl().id());
        assertEquals(expected, full.originAccessControl().originAccessControlConfig());
        assertEquals(expected, config.originAccessControlConfig());
        assertFalse(full.eTag().isBlank());
        assertEquals(full.eTag(), config.eTag());
        return full.eTag();
    }
}
