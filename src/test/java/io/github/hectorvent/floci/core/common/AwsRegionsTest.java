package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsRegionsTest {

    /**
     * {@code ALL} is what the emulator advertises; {@code KNOWN_IDS} is what it recognises. The
     * second must contain the first, or DescribeRegions could name a region that hostname parsing
     * refuses to read back.
     */
    @Test
    void everyAdvertisedRegionIsAKnownRegionId() {
        for (String region : AwsRegions.ALL) {
            assertTrue(AwsRegions.KNOWN_IDS.contains(region),
                    region + " is advertised by DescribeRegions but is not a known region id");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-1", "ap-northeast-3", "us-gov-west-1",
            "cn-northwest-1", "il-central-1", "US-EAST-1"})
    void realRegionIdsAreRecognised(String label) {
        assertTrue(AwsRegions.isRegionId(label));
    }

    /**
     * The point of an id list over a pattern: these all match the region <em>shape</em>
     * {@code [a-z]{2}-[a-z-]+-\d+} and none of them is a region. Treating them as regions is what
     * made {@code data.my-cd-1} unreachable as an S3 bucket.
     */
    @ParameterizedTest
    @ValueSource(strings = {"my-cd-1", "eu-team-2", "us-west-9", "ap-corp-1", "no-such-region-12"})
    void regionShapedStringsThatAreNotRegionsAreRejected(String label) {
        assertFalse(AwsRegions.isRegionId(label));
    }

    @Test
    void nullIsNotARegionId() {
        assertFalse(AwsRegions.isRegionId(null));
    }

    /**
     * One row per partition botocore defines, so a partition going missing is a failing test
     * rather than a silently commercial ARN.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,       aws,        amazonaws.com",
            "eu-west-1,       aws,        amazonaws.com",
            "us-gov-west-1,   aws-us-gov, amazonaws.com",
            "us-gov-east-1,   aws-us-gov, amazonaws.com",
            "cn-north-1,      aws-cn,     amazonaws.com.cn",
            "cn-northwest-1,  aws-cn,     amazonaws.com.cn",
            "us-iso-east-1,   aws-iso,    c2s.ic.gov",
            "us-isob-east-1,  aws-iso-b,  sc2s.sgov.gov",
            "eu-isoe-west-1,  aws-iso-e,  cloud.adc-e.uk",
            "us-isof-south-1, aws-iso-f,  csp.hci.ic.gov",
            "eusc-de-east-1,  aws-eusc,   amazonaws.eu"})
    void everyPartitionResolvesFromItsRegion(String region, String partition, String dnsSuffix) {
        assertEquals(partition, AwsRegions.partitionFor(region));
        assertEquals(dnsSuffix, AwsRegions.dnsSuffixFor(region));
    }

    /**
     * The pair that would collide under a naive prefix check: {@code us-isob-east-1} is
     * {@code aws-iso-b}, not {@code aws-iso}.
     */
    @Test
    void isobIsNotIso() {
        assertEquals("aws-iso", AwsRegions.partitionFor("us-iso-west-1"));
        assertEquals("aws-iso-b", AwsRegions.partitionFor("us-isob-west-1"));
    }

    /**
     * Blank is the common case, not an error: a global-service ARN carries no region and keeps
     * the commercial partition.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "xx-nowhere-9", "not-a-region", "us-gov", "cn-north"})
    void blankAndUnrecognisedRegionsFallBackToCommercial(String region) {
        assertEquals("aws", AwsRegions.partitionFor(region));
        assertEquals("amazonaws.com", AwsRegions.dnsSuffixFor(region));
    }

    @Test
    void regionMatchingIsCaseInsensitive() {
        assertEquals("aws-cn", AwsRegions.partitionFor("CN-NORTH-1"));
    }

    /** Every GovCloud and China id the emulator recognises must land outside {@code aws}. */
    @Test
    void knownNonCommercialIdsAreNotCommercial() {
        for (String region : AwsRegions.KNOWN_IDS) {
            if (region.startsWith("us-gov-")) {
                assertEquals("aws-us-gov", AwsRegions.partitionFor(region), region);
            } else if (region.startsWith("cn-")) {
                assertEquals("aws-cn", AwsRegions.partitionFor(region), region);
            } else {
                assertEquals("aws", AwsRegions.partitionFor(region), region);
            }
        }
    }
}
