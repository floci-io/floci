package io.floci.aws.services.cloudfront;

import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.NoSuchOriginAccessControlException;
import software.amazon.awssdk.services.cloudfront.model.OriginAccessControlConfig;

import java.util.UUID;

final class CloudFrontOriginAccessControlFixtures {

    private CloudFrontOriginAccessControlFixtures() {
    }

    static OriginAccessControlConfig configuration() {
        return OriginAccessControlConfig.builder().name("oac-test-" + UUID.randomUUID())
                .description("owned test control").originAccessControlOriginType("s3")
                .signingProtocol("sigv4").signingBehavior("always").build();
    }

    static void deleteIfPresent(CloudFrontClient client, String id) {
        try {
            String etag = client.getOriginAccessControlConfig(request -> request.id(id)).eTag();
            client.deleteOriginAccessControl(request -> request.id(id).ifMatch(etag));
        } catch (NoSuchOriginAccessControlException ignored) {
            // The lifecycle assertion may already have deleted this owned control.
        }
    }
}
