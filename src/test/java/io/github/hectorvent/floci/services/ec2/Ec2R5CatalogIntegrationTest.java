package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;
import software.amazon.awssdk.services.ec2.model.NetworkCardInfo;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class Ec2R5CatalogIntegrationTest {

    @Test
    void r5LargeMetadataAndAvailabilityZoneOfferingsAgree() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeInfo> types = ec2.describeInstanceTypes(r -> r.instanceTypes(InstanceType.R5_LARGE))
                    .instanceTypes();
            assertThat(types, hasSize(1));
            InstanceTypeInfo type = types.getFirst();
            assertEquals(InstanceType.R5_LARGE, type.instanceType());
            assertEquals(2, type.vCpuInfo().defaultVCpus());
            assertEquals(16384L, type.memoryInfo().sizeInMiB());
            assertThat(type.processorInfo().supportedArchitecturesAsStrings(), contains("x86_64"));
            assertFalse(type.instanceStorageSupported());
            assertFalse(type.burstablePerformanceSupported());
            assertThat(type.supportedUsageClassesAsStrings(), containsInAnyOrder("on-demand", "spot"));
            assertFalse(type.networkInfo().encryptionInTransitSupported());
            assertEquals(0, type.networkInfo().defaultNetworkCardIndex());
            assertEquals(10, type.networkInfo().ipv4AddressesPerInterface());
            assertThat(type.networkInfo().networkCards(), hasSize(1));
            NetworkCardInfo card = type.networkInfo().networkCards().getFirst();
            assertEquals(0, card.networkCardIndex());
            assertEquals(3, card.maximumNetworkInterfaces());

            String zone = ec2.describeAvailabilityZones().availabilityZones().get(0).zoneName();
            List<InstanceTypeOffering> offerings = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.AVAILABILITY_ZONE)
                    .filters(
                            f -> f.name("instance-type").values("r5.large"),
                            f -> f.name("location").values(zone)))
                    .instanceTypeOfferings();
            assertThat(offerings, hasSize(1));
            InstanceTypeOffering offering = offerings.getFirst();
            assertEquals(InstanceType.R5_LARGE, offering.instanceType());
            assertEquals(LocationType.AVAILABILITY_ZONE, offering.locationType());
            assertEquals(zone, offering.location());
        }
    }
}
