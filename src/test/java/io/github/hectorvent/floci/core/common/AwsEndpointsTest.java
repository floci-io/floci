package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hostnames AWS-shaped response fields carry. The S3 website form and the EC2 public DNS
 * name are the two shapes that are not a plain {@code service.region.suffix}, and both were
 * wrong in the commercial partition before this helper existed.
 */
class AwsEndpointsTest {

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void regionalHostsCarryThePartitionSuffix(PartitionCase partitionCase) {
        String region = partitionCase.region();
        PartitionMatrix.assertHostIn(partitionCase, AwsEndpoints.host("ec2", region));
        assertEquals("ec2." + region + "." + partitionCase.dnsSuffix(), AwsEndpoints.host("ec2", region));
        assertEquals("abc123.execute-api." + region + "." + partitionCase.dnsSuffix(),
                AwsEndpoints.executeApiHost("abc123", region));
        assertEquals("b.s3." + partitionCase.dnsSuffix(), AwsEndpoints.s3Host("b", region));
        assertEquals("b.s3." + region + "." + partitionCase.dnsSuffix(), AwsEndpoints.s3RegionalHost("b", region));
        assertEquals("b.s3.dualstack." + region + "." + partitionCase.dnsSuffix(), AwsEndpoints.s3DualStackHost("b", region));
    }

    /**
     * The nine regions before the CDK's {@code RULE_S3_WEBSITE_REGIONAL_SUBDOMAIN} keep the
     * dash form; everything after it uses the dot form. CloudFormation's own {@code WebsiteURL}
     * example ends with a slash ({@code http://mystack-mybucket-kdwwxmddtr2g.s3-website-us-east-2.amazonaws.com/}).
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      b.s3-website-us-east-1.amazonaws.com",
            "eu-west-1,      b.s3-website-eu-west-1.amazonaws.com",
            "us-west-1,      b.s3-website-us-west-1.amazonaws.com",
            "ap-southeast-1, b.s3-website-ap-southeast-1.amazonaws.com",
            "ap-northeast-1, b.s3-website-ap-northeast-1.amazonaws.com",
            "us-gov-west-1,  b.s3-website-us-gov-west-1.amazonaws.com",
            "us-west-2,      b.s3-website-us-west-2.amazonaws.com",
            "sa-east-1,      b.s3-website-sa-east-1.amazonaws.com",
            "ap-southeast-2, b.s3-website-ap-southeast-2.amazonaws.com",
            "us-east-2,      b.s3-website.us-east-2.amazonaws.com",
            "eu-central-1,   b.s3-website.eu-central-1.amazonaws.com",
            "us-gov-east-1,  b.s3-website.us-gov-east-1.amazonaws.com",
            "cn-north-1,     b.s3-website.cn-north-1.amazonaws.com.cn",
            "us-iso-east-1,  b.s3-website.us-iso-east-1.c2s.ic.gov",
            "xx-nowhere-9,   b.s3-website.xx-nowhere-9.amazonaws.com"})
    void s3WebsiteHostUsesTheDashFormOnlyForTheNineLegacyRegions(String region, String host) {
        assertEquals(host, AwsEndpoints.s3WebsiteHost("b", region));
        assertEquals("http://" + host + "/", AwsEndpoints.s3WebsiteUrl("b", region));
    }

    @ParameterizedTest
    @CsvSource({
            "us-east-1,     ec2-203-0-113-7.compute-1.amazonaws.com",
            "us-west-2,     ec2-203-0-113-7.us-west-2.compute.amazonaws.com",
            "us-gov-west-1, ec2-203-0-113-7.us-gov-west-1.compute.amazonaws.com",
            "cn-north-1,    ec2-203-0-113-7.cn-north-1.compute.amazonaws.com.cn",
            "eusc-de-east-1, ec2-203-0-113-7.eusc-de-east-1.compute.amazonaws.eu"})
    void ec2PublicDnsIsComputeOneOnlyInVirginia(String region, String expected) {
        assertEquals(expected, AwsEndpoints.ec2PublicDns("203.0.113.7", region));
    }

    @Test
    void s3DualStackAvailabilityFollowsEndpointsJson() {
        assertEquals(true, AwsPartitions.commercial().supportsS3DualStack("us-east-1"));
        assertEquals(true, AwsPartitions.byId("aws-cn").supportsS3DualStack("cn-north-1"));
        assertEquals(true, AwsPartitions.byId("aws-iso").supportsS3DualStack("us-iso-east-1"));
        assertEquals(true, AwsPartitions.byId("aws-iso-b").supportsS3DualStack("us-isob-east-1"));
        assertEquals(false, AwsPartitions.byId("aws-iso-b").supportsS3DualStack("us-isob-west-1"));
        assertEquals(false, AwsPartitions.byId("aws-iso-e").supportsS3DualStack("eu-isoe-west-1"));
        assertEquals(false, AwsPartitions.byId("aws-iso-f").supportsS3DualStack("us-isof-south-1"));
        assertEquals(false, AwsPartitions.byId("aws-eusc").supportsS3DualStack("eusc-de-east-1"));
        assertEquals(false, AwsPartitions.commercial().supportsS3DualStack(null));
    }
}
