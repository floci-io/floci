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
        CloudFrontEdgeDomain edge = CloudFrontEdgeDomain.create("us-east-1", "cloudfront.net");

        assertTrue(edge.domainName().matches("d[0-9a-f]{13}\\.cloudfront\\.net"), edge.domainName());
        assertNotEquals(edge.domainName(), CloudFrontEdgeDomain.create("us-east-1", "cloudfront.net").domainName());
    }

    @Test
    void hostedZoneIsThePartitionsCloudFrontZone() {
        assertEquals("Z2FDTNDATAQYW2", CloudFrontEdgeDomain.create("eu-west-1", "cloudfront.net").hostedZoneId());
        assertEquals("Z3RFFRIM2A3IF5", CloudFrontEdgeDomain.create("cn-north-1", "cloudfront.net").hostedZoneId());
        assertNull(CloudFrontEdgeDomain.create("us-gov-west-1", "cloudfront.net").hostedZoneId(),
                "GovCloud has no CloudFront");
    }
}
