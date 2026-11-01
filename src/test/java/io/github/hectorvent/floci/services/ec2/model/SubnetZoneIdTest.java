package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #5580: subnets persisted before zone ids took AWS's shape stored {@code <region>-az<n>};
 * the id is derived from the zone name, so the old form reads back as the current one.
 */
class SubnetZoneIdTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theStoredLegacyIdReadsBackAsTheCurrentId() throws Exception {
        Subnet subnet = mapper.readValue("""
                {
                  "subnetId": "subnet-legacy",
                  "availabilityZone": "ap-southeast-1a",
                  "availabilityZoneId": "ap-southeast-1-az1",
                  "region": "ap-southeast-1"
                }
                """, Subnet.class);

        assertEquals("apse1-az1", subnet.getAvailabilityZoneId());
    }

    @Test
    void theRoundTripWritesTheCurrentIdAndReadsItBack() throws Exception {
        Subnet legacy = mapper.readValue("""
                {
                  "availabilityZoneId": "ap-southeast-1-az3",
                  "availabilityZone": "ap-southeast-1c",
                  "region": "ap-southeast-1"
                }
                """, Subnet.class);

        String written = mapper.writeValueAsString(legacy);
        Subnet reread = mapper.readValue(written, Subnet.class);

        assertEquals("apse1-az3", mapper.readTree(written).get("availabilityZoneId").asText());
        assertEquals("apse1-az3", reread.getAvailabilityZoneId());
        assertEquals("ap-southeast-1c", reread.getAvailabilityZone());
    }

    @Test
    void aSubnetWithoutAZoneNameKeepsTheStoredId() throws Exception {
        Subnet subnet = mapper.readValue("""
                {"availabilityZoneId": "use1-az2", "region": "us-east-1"}
                """, Subnet.class);

        assertEquals("use1-az2", subnet.getAvailabilityZoneId());
    }
}
