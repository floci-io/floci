package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateResponseHeadersPolicyResponse;
import software.amazon.awssdk.services.cloudfront.model.NoSuchResponseHeadersPolicyException;
import software.amazon.awssdk.services.cloudfront.model.PreconditionFailedException;
import software.amazon.awssdk.services.cloudfront.model.ResponseHeadersPolicyConfig;
import software.amazon.awssdk.services.cloudfront.model.ResponseHeadersPolicyType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CloudFrontResponseHeadersPolicyTest {

    @Test
    void roundTripsResponseHeadersPolicyWithTheAwsSdk() {
        assumeFalse(
                TestFixtures.isRealAws(),
                "The immediate policy lifecycle in this test is emulator-specific");

        String suffix = TestFixtures.uniqueName("cloudfront-response-headers");
        String policyId = null;
        String etag = null;

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            CreateResponseHeadersPolicyResponse created =
                    cloudFront.createResponseHeadersPolicy(request -> request
                            .responseHeadersPolicyConfig(policyConfig(
                                    suffix, "created", "created-value")));
            policyId = created.responseHeadersPolicy().id();
            etag = created.eTag();
            assertNotNull(policyId);
            assertNotNull(etag);
            assertTrue(created.location().endsWith("/" + policyId));
            assertNotNull(created.responseHeadersPolicy().lastModifiedTime());
            assertEquals("policy-" + suffix,
                    created.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .name());

            String id = policyId;
            var fetched = cloudFront.getResponseHeadersPolicy(
                    request -> request.id(id));
            assertEquals(etag, fetched.eTag());
            assertEquals("created",
                    fetched.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .comment());
            assertEquals("created-value",
                    fetched.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .customHeadersConfig()
                            .items()
                            .get(0)
                            .value());
            assertEquals(1,
                    fetched.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .customHeadersConfig()
                            .quantity());
            assertEquals("X-Floci-Policy",
                    fetched.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .customHeadersConfig()
                            .items()
                            .get(0)
                            .header());
            assertTrue(fetched.responseHeadersPolicy()
                    .responseHeadersPolicyConfig()
                    .customHeadersConfig()
                    .items()
                    .get(0)
                    .override());

            var fetchedConfig = cloudFront.getResponseHeadersPolicyConfig(
                    request -> request.id(id));
            assertEquals(etag, fetchedConfig.eTag());
            assertEquals("created-value",
                    fetchedConfig.responseHeadersPolicyConfig()
                            .customHeadersConfig()
                            .items()
                            .get(0)
                            .value());
            assertEquals(0.0,
                    fetchedConfig.responseHeadersPolicyConfig()
                            .serverTimingHeadersConfig()
                            .samplingRate());
            assertTrue(fetchedConfig.responseHeadersPolicyConfig()
                    .serverTimingHeadersConfig()
                    .enabled());

            var listed = cloudFront.listResponseHeadersPolicies(request -> request
                    .type(ResponseHeadersPolicyType.CUSTOM)
                    .maxItems("100"))
                    .responseHeadersPolicyList();
            assertTrue(listed.items().stream().anyMatch(summary ->
                    id.equals(summary.responseHeadersPolicy().id())
                            && summary.type() == ResponseHeadersPolicyType.CUSTOM));
            assertTrue(listed.quantity() >= 1);

            assertThrows(PreconditionFailedException.class,
                    () -> cloudFront.updateResponseHeadersPolicy(request -> request
                            .id(id)
                            .ifMatch("stale-etag")
                            .responseHeadersPolicyConfig(policyConfig(
                                    suffix, "stale", "stale-value"))));

            var updated = cloudFront.updateResponseHeadersPolicy(request -> request
                    .id(id)
                    .ifMatch(created.eTag())
                    .responseHeadersPolicyConfig(policyConfig(
                            suffix, "updated", "updated-value")));
            etag = updated.eTag();
            assertNotNull(etag);
            assertNotEquals(created.eTag(), etag);
            assertEquals("updated",
                    updated.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .comment());
            assertEquals("updated-value",
                    updated.responseHeadersPolicy()
                            .responseHeadersPolicyConfig()
                            .customHeadersConfig()
                            .items()
                            .get(0)
                            .value());

            String currentEtag = etag;
            cloudFront.deleteResponseHeadersPolicy(request -> request
                    .id(id)
                    .ifMatch(currentEtag));
            policyId = null;
            assertThrows(NoSuchResponseHeadersPolicyException.class,
                    () -> cloudFront.getResponseHeadersPolicy(
                            request -> request.id(id)));
        } finally {
            if (policyId != null && etag != null) {
                try (CloudFrontClient cloudFront =
                             TestFixtures.cloudFrontClient()) {
                    String id = policyId;
                    String currentEtag = cloudFront
                            .getResponseHeadersPolicyConfig(
                                    request -> request.id(id))
                            .eTag();
                    cloudFront.deleteResponseHeadersPolicy(request -> request
                            .id(id)
                            .ifMatch(currentEtag));
                }
            }
        }
    }

    private static ResponseHeadersPolicyConfig policyConfig(
            String suffix, String comment, String headerValue) {
        return ResponseHeadersPolicyConfig.builder()
                .name("policy-" + suffix)
                .comment(comment)
                .customHeadersConfig(custom -> custom
                        .quantity(1)
                        .items(header -> header
                                .header("X-Floci-Policy")
                                .value(headerValue)
                                .override(true)))
                .serverTimingHeadersConfig(serverTiming -> serverTiming
                        .enabled(true)
                        .samplingRate(0.0))
                .build();
    }
}
