package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
