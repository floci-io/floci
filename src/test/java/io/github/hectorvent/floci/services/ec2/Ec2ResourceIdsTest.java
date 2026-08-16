package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Ec2ResourceIdsTest {

    @Test
    void mapsIdPrefixesToArnResourceTypes() {
        assertEquals("volume", Ec2ResourceIds.resourceType("vol-0abc"));
        assertEquals("instance", Ec2ResourceIds.resourceType("i-0abc"));
        assertEquals("security-group", Ec2ResourceIds.resourceType("sg-0abc"));
        assertEquals("network-interface", Ec2ResourceIds.resourceType("eni-0abc"));
        assertEquals("elastic-ip", Ec2ResourceIds.resourceType("eipalloc-0abc"));
        assertEquals("image", Ec2ResourceIds.resourceType("ami-0abc"));
    }

    /** {@code igw-} and {@code ipam-} both start with {@code i-}'s prefix character. */
    @Test
    void prefersTheLongestMatchingPrefix() {
        assertEquals("internet-gateway", Ec2ResourceIds.resourceType("igw-0abc"));
        assertEquals("ipam", Ec2ResourceIds.resourceType("ipam-0abc"));
        assertEquals("security-group-rule", Ec2ResourceIds.resourceType("sgr-0abc"));
        assertEquals("vpc-endpoint-service", Ec2ResourceIds.resourceType("vpce-svc-0abc"));
        assertEquals("vpc-endpoint", Ec2ResourceIds.resourceType("vpce-0abc"));
        assertEquals("transit-gateway-attachment", Ec2ResourceIds.resourceType("tgw-attach-0abc"));
        assertEquals("transit-gateway-route-table", Ec2ResourceIds.resourceType("tgw-rtb-0abc"));
        assertEquals("transit-gateway", Ec2ResourceIds.resourceType("tgw-0abc"));
    }

    @Test
    void reportsUnknownForAnUnrecognisedPrefix() {
        assertEquals("unknown", Ec2ResourceIds.resourceType("zzz-0abc"));
        assertEquals("unknown", Ec2ResourceIds.resourceType(null));
    }

    @Test
    void buildsTheArnAwsWouldBuild() {
        assertEquals("arn:aws:ec2:us-east-1:000000000000:volume/vol-0abc",
                Ec2ResourceIds.arn("us-east-1", "000000000000", "vol-0abc"));
        assertEquals("arn:aws:ec2:us-west-2:000000000000:security-group/sg-0abc",
                Ec2ResourceIds.arn("us-west-2", "000000000000", "sg-0abc"));
    }

    /** AWS documents image and snapshot ARNs without an account segment. */
    @Test
    void omitsTheAccountWhereAwsOmitsIt() {
        assertEquals("arn:aws:ec2:us-east-1::image/ami-0abc",
                Ec2ResourceIds.arn("us-east-1", "000000000000", "ami-0abc"));
        assertEquals("arn:aws:ec2:us-east-1::snapshot/snap-0abc",
                Ec2ResourceIds.arn("us-east-1", "000000000000", "snap-0abc"));
    }

    /** A guessed ARN would be joined on by callers, so an unknown prefix yields none. */
    @Test
    void producesNoArnForAnUnrecognisedId() {
        assertNull(Ec2ResourceIds.arn("us-east-1", "000000000000", "zzz-0abc"));
    }
}
