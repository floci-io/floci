package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvailabilityZoneIdsTest {

    /**
     * Prefixes AWS publishes: the commercial ones from the Regions and Zones guide, China's from
     * moto's zone table, GovCloud West's from the Terraform provider's acceptance configuration.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      use1",
            "us-west-2,      usw2",
            "ap-northeast-1, apne1",
            "ap-southeast-4, apse4",
            "eu-central-2,   euc2",
            "mx-central-1,   mxc1",
            "af-south-1,     afs1",
            "cn-north-1,     cnn1",
            "cn-northwest-1, cnnw1",
            "us-gov-west-1,  usgw1",
            "us-gov-east-1,  usge1"})
    void aPublishedRegionGetsAwsPrefix(String region, String prefix) {
        assertEquals(prefix, AvailabilityZoneIds.prefix(region));
        assertEquals(prefix + "-az1", AvailabilityZoneIds.forZoneName(region, region + "a"));
        assertEquals(prefix + "-az3", AvailabilityZoneIds.forZoneName(region, region + "c"));
    }

    /** No zone id is published for these regions, so none is made up: the region is the prefix. */
    @ParameterizedTest
    @CsvSource({"us-iso-east-1", "us-isob-east-1", "eu-isoe-west-1", "us-isof-south-1", "eusc-de-east-1"})
    void aRegionWithoutAPublishedPrefixKeepsItsName(String region) {
        assertEquals(region, AvailabilityZoneIds.prefix(region));
        assertEquals(region + "-az2", AvailabilityZoneIds.forZoneName(region, region + "b"));
    }

    @Test
    void aZoneNameOutsideTheRegionKeepsTheFirstZonesId() {
        assertEquals("use1-az1", AvailabilityZoneIds.forZoneName("us-east-1", "eu-west-1b"));
        assertEquals("use1-az1", AvailabilityZoneIds.forZoneName("us-east-1", null));
    }

    @Test
    void indexOfReadsBackTheIdsThisRegionPublishes() {
        assertEquals(2, AvailabilityZoneIds.indexOf("us-east-1", "use1-az2"));
        assertEquals(1, AvailabilityZoneIds.indexOf("us-iso-east-1", "us-iso-east-1-az1"));
        assertEquals(-1, AvailabilityZoneIds.indexOf("us-east-1", "usw2-az2"));
        assertEquals(-1, AvailabilityZoneIds.indexOf("us-east-1", "us-east-1-az2"));
        assertEquals(-1, AvailabilityZoneIds.indexOf("us-east-1", "use1-azx"));
        assertEquals(-1, AvailabilityZoneIds.indexOf("us-east-1", null));
    }
}
