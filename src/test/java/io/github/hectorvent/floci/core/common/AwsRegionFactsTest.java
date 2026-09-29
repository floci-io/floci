package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vendored per-region tables, pinned against the Terraform and CDK rows they came from. */
class AwsRegionFactsTest {

    @ParameterizedTest
    @CsvSource({
            "us-east-1,      Z35SXDOTRQ7X7K,        Z26RNL4JYFTOTI,        Z3AQBSTGFYJSTF",
            "cn-north-1,     Z1GDH35T77C1KE,        Z3QFB96KMJ7ED6,        Z5CN8UMXT92WN",
            "us-gov-west-1,  Z33AYJ8TM3BH4J,        ZMG1MZ2THAWF1,         Z31GFT0UA1I2HV",
            "af-south-1,     Z268VQBMOI5EKX,        Z203XCE67M25HM,        Z83WF9RJE8B12",
            "mx-central-1,   Z023552324OKD1BB28BH5, Z02031231H3ID6HYJ9A7U, Z057606446ZNVQJJ8WOP"})
    void loadBalancerAndWebsiteZonesFollowTheTerraformTables(String region, String alb, String nlb, String website) {
        assertEquals(Optional.of(alb), AwsRegionFacts.albHostedZoneId(region));
        assertEquals(Optional.of(alb), AwsRegionFacts.classicElbHostedZoneId(region), "classic shares the ALB zone");
        assertEquals(Optional.of(nlb), AwsRegionFacts.nlbHostedZoneId(region));
        assertEquals(Optional.of(website), AwsRegionFacts.s3WebsiteHostedZoneId(region));
    }

    /** The ISO-F website zones exist only in the CDK table; the ISO load balancer zones nowhere. */
    @Test
    void isoRegionsHaveOnlyWhatIsPublished() {
        assertEquals(Optional.of("Z03376072I8GXC2DXUFXI"), AwsRegionFacts.s3WebsiteHostedZoneId("us-isof-south-1"));
        assertTrue(AwsRegionFacts.albHostedZoneId("us-isof-south-1").isEmpty());
        assertTrue(AwsRegionFacts.nlbHostedZoneId("us-iso-east-1").isEmpty());
        assertTrue(AwsRegionFacts.s3WebsiteHostedZoneId("eusc-de-east-1").isEmpty());
        assertTrue(AwsRegionFacts.albHostedZoneId("xx-nowhere-9").isEmpty());
        assertTrue(AwsRegionFacts.albHostedZoneId(null).isEmpty());
    }

    @Test
    void cloudFrontZoneAndSamlUrlArePerPartition() {
        assertEquals(Optional.of("Z2FDTNDATAQYW2"), AwsRegionFacts.cloudFrontHostedZoneId("aws"));
        assertEquals(Optional.of("Z3RFFRIM2A3IF5"), AwsRegionFacts.cloudFrontHostedZoneId("aws-cn"));
        assertTrue(AwsRegionFacts.cloudFrontHostedZoneId("aws-us-gov").isEmpty());
        assertEquals(Optional.of("https://signin.aws.amazon.com/saml"), AwsRegionFacts.samlSignOnUrl("aws"));
        assertEquals(Optional.of("https://signin.amazonaws.cn/saml"), AwsRegionFacts.samlSignOnUrl("aws-cn"));
        assertEquals(Optional.of("https://signin.amazonaws-us-gov.com/saml"), AwsRegionFacts.samlSignOnUrl("aws-us-gov"));
        assertEquals(Optional.of("https://signin.sc2shome.sgov.gov/saml"), AwsRegionFacts.samlSignOnUrl("aws-iso-b"));
        assertTrue(AwsRegionFacts.samlSignOnUrl("aws-eusc").isEmpty());
        assertTrue(AwsRegionFacts.samlSignOnUrl(null).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "us-east-1,       s3,         com.amazonaws.us-east-1.s3",
            "us-gov-west-1,   s3,         com.amazonaws.us-gov-west-1.s3",
            "cn-north-1,      s3,         cn.com.amazonaws.cn-north-1.s3",
            "cn-north-1,      dynamodb,   com.amazonaws.cn-north-1.dynamodb",
            "cn-northwest-1,  transcribe, cn.com.amazonaws.cn-northwest-1.transcribe.cn",
            "us-iso-east-1,   ecr.api,    gov.ic.c2s.us-iso-east-1.ecr.api",
            "us-iso-east-1,   s3,         com.amazonaws.us-iso-east-1.s3",
            "eusc-de-east-1,  execute-api, eu.amazonaws.eusc-de-east-1.execute-api",
            "us-isof-south-1, ebs,        gov.ic.hci.csp.us-isof-south-1.ebs"})
    void vpcEndpointServiceNamesReverseTheSuffixOnlyForTheListedPairs(String region, String service, String expected) {
        assertEquals(expected, AwsRegionFacts.vpcEndpointServiceName(region, service));
    }

    @Test
    void vpcEndpointServiceNamePrefixIsTheReversedSuffix() {
        assertEquals(Optional.of("com.amazonaws.vpce"), AwsRegionFacts.vpcEndpointServiceNamePrefix("aws"));
        assertEquals(Optional.of("com.amazonaws.vpce"), AwsRegionFacts.vpcEndpointServiceNamePrefix("aws-us-gov"));
        assertEquals(Optional.of("cn.com.amazonaws.vpce"), AwsRegionFacts.vpcEndpointServiceNamePrefix("aws-cn"));
        assertEquals(Optional.of("gov.sgov.sc2s.vpce"), AwsRegionFacts.vpcEndpointServiceNamePrefix("aws-iso-b"));
        assertEquals(52, AwsRegionFacts.vpcEndpointPrefixServices("cn-north-1").size());
        assertTrue(AwsRegionFacts.vpcEndpointPrefixServices("us-east-1").isEmpty());
    }
}
