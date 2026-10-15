package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CloudFront front an edge-optimized API Gateway domain and an AppSync custom domain share. */
class CloudFrontEdgeDomainTest {

    @Test
    void namesADistributionOnTheConfiguredSuffix() {
        CloudFrontEdgeDomain edge = CloudFrontEdgeDomain.create("aws", "cloudfront.net");

        assertTrue(edge.domainName().matches("d[0-9a-f]{13}\\.cloudfront\\.net"), edge.domainName());
        assertNotEquals(edge.domainName(), CloudFrontEdgeDomain.create("aws", "cloudfront.net").domainName());
    }

    @Test
    void anUnconfiguredSuffixIsThePartitionsOwn() {
        assertTrue(CloudFrontEdgeDomain.create("aws", null).domainName().endsWith(".cloudfront.net"));
        assertTrue(CloudFrontEdgeDomain.create("aws-cn", null).domainName().endsWith(".cloudfront.cn"));
        assertEquals("cloudfront.net", CloudFrontEdgeDomain.domainSuffix(null, "aws"));
        assertEquals("cloudfront.cn", CloudFrontEdgeDomain.domainSuffix(null, "aws-cn"));
        assertEquals("cloudfront.cn", CloudFrontEdgeDomain.domainSuffix(" ", "aws-cn"), "blank is unset");
        assertEquals("cloudfront.net", CloudFrontEdgeDomain.domainSuffix(null, "aws-us-gov"),
                "a partition without CloudFront keeps the commercial suffix");
        assertEquals("cloudfront.net", CloudFrontEdgeDomain.domainSuffix(null, null));
    }

    @Test
    void aConfiguredSuffixWinsInEveryPartition() {
        assertEquals("cloudfront.local", CloudFrontEdgeDomain.domainSuffix("cloudfront.local", "aws-cn"));
        assertTrue(CloudFrontEdgeDomain.create("aws-cn", "cloudfront.net").domainName().endsWith(".cloudfront.net"));
    }

    @Test
    void hostedZoneIsThePartitionsCloudFrontZone() {
        assertEquals("Z2FDTNDATAQYW2", CloudFrontEdgeDomain.create("aws", "cloudfront.net").hostedZoneId());
        assertEquals("Z3RFFRIM2A3IF5", CloudFrontEdgeDomain.create("aws-cn", "cloudfront.net").hostedZoneId());
        assertNull(CloudFrontEdgeDomain.create("aws-us-gov", "cloudfront.net").hostedZoneId(),
                "GovCloud has no CloudFront");
    }
}
