package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.CustomHeaders;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginCustomHeader;
import software.amazon.awssdk.services.cloudfront.model.S3OriginConfig;
import software.amazon.awssdk.services.cloudfront.model.ViewerProtocolPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CloudFrontOriginCustomHeadersTest {

    @Test
    void roundTripsOriginCustomHeadersWithTheAwsSdk() {
        assumeFalse(
                TestFixtures.isRealAws(),
                "The immediate distribution lifecycle in this test is emulator-specific");

        String suffix = TestFixtures.uniqueName("cloudfront-origin-headers");
        String distributionId = null;

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            Origin configuredOrigin = Origin.builder()
                    .id("configured-origin")
                    .domainName("configured-content.s3.amazonaws.com")
                    .customHeaders(CustomHeaders.builder()
                            .quantity(1)
                            .items(OriginCustomHeader.builder()
                                    .headerName("X-Origin-Verify")
                                    .headerValue("configured-value")
                                    .build())
                            .build())
                    .s3OriginConfig(S3OriginConfig.builder()
                            .originAccessIdentity("")
                            .build())
                    .build();
            Origin emptyOrigin = Origin.builder()
                    .id("empty-origin")
                    .domainName("empty-content.s3.amazonaws.com")
                    .s3OriginConfig(S3OriginConfig.builder()
                            .originAccessIdentity("")
                            .build())
                    .build();
            DistributionConfig configuration = DistributionConfig.builder()
                    .callerReference("distribution-reference-" + suffix)
                    .enabled(true)
                    .origins(origins -> origins
                            .quantity(2)
                            .items(configuredOrigin, emptyOrigin))
                    .defaultCacheBehavior(behavior -> behavior
                            .targetOriginId(configuredOrigin.id())
                            .viewerProtocolPolicy(ViewerProtocolPolicy.ALLOW_ALL))
                    .build();

            CreateDistributionResponse created = cloudFront.createDistribution(
                    request -> request.distributionConfig(configuration));
            distributionId = created.distribution().id();
            assertOriginShapes(created.distribution()
                    .distributionConfig()
                    .origins()
                    .items());

            GetDistributionConfigResponse fetched = cloudFront.getDistributionConfig(
                    request -> request.id(created.distribution().id()));
            assertOriginShapes(fetched.distributionConfig().origins().items());

            var listed = cloudFront.listDistributions(request -> request.maxItems("100"))
                    .distributionList()
                    .items()
                    .stream()
                    .filter(summary -> created.distribution().id().equals(summary.id()))
                    .findFirst()
                    .orElseThrow();
            assertOriginShapes(listed.origins().items());
        } finally {
            if (distributionId != null) {
                try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
                    String id = distributionId;
                    GetDistributionConfigResponse current = cloudFront.getDistributionConfig(
                            request -> request.id(id));
                    var disabled = current.distributionConfig()
                            .toBuilder()
                            .enabled(false)
                            .build();
                    var updated = cloudFront.updateDistribution(request -> request
                            .id(id)
                            .ifMatch(current.eTag())
                            .distributionConfig(disabled));
                    cloudFront.deleteDistribution(request -> request
                            .id(id)
                            .ifMatch(updated.eTag()));
                }
            }
        }
    }

    private static void assertOriginShapes(List<Origin> origins) {
        Origin configured = originById(origins, "configured-origin");
        assertNotNull(configured.customHeaders());
        assertEquals(1, configured.customHeaders().quantity());
        assertEquals(1, configured.customHeaders().items().size());
        assertEquals(
                "X-Origin-Verify",
                configured.customHeaders().items().get(0).headerName());
        assertEquals(
                "configured-value",
                configured.customHeaders().items().get(0).headerValue());

        Origin empty = originById(origins, "empty-origin");
        assertNotNull(empty.customHeaders());
        assertEquals(0, empty.customHeaders().quantity());
        assertFalse(empty.customHeaders().hasItems());
    }

    private static Origin originById(List<Origin> origins, String id) {
        return origins.stream()
                .filter(origin -> id.equals(origin.id()))
                .findFirst()
                .orElseThrow();
    }
}
