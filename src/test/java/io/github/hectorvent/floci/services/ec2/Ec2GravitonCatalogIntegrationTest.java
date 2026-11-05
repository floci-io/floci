package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
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
class Ec2GravitonCatalogIntegrationTest {

    @ParameterizedTest
    @CsvFileSource(resources = "/ec2/graviton-instance-types.csv", numLinesToSkip = 1)
    void gravitonMetadataAndOfferingsMatchAwsSnapshot(String name, int vcpus, long memoryMib,
            int maxInterfaces, int ipv4PerInterface, boolean encryptionInTransit, boolean currentGeneration) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeInfo> types = ec2.describeInstanceTypes(r -> r.instanceTypesWithStrings(name)).instanceTypes();
            assertThat(types, hasSize(1));
            InstanceTypeInfo type = types.getFirst();
            assertEquals(name, type.instanceTypeAsString());
            assertEquals(vcpus, type.vCpuInfo().defaultVCpus());
            assertEquals(memoryMib, type.memoryInfo().sizeInMiB());
            assertThat(type.processorInfo().supportedArchitecturesAsStrings(), contains("arm64"));
            assertEquals(currentGeneration, type.currentGeneration());
            assertFalse(type.instanceStorageSupported());
            assertFalse(type.burstablePerformanceSupported());
            assertThat(type.supportedUsageClassesAsStrings(), containsInAnyOrder("on-demand", "spot"));
            assertEquals(encryptionInTransit, type.networkInfo().encryptionInTransitSupported());
            assertEquals(0, type.networkInfo().defaultNetworkCardIndex());
            assertEquals(ipv4PerInterface, type.networkInfo().ipv4AddressesPerInterface());
            assertThat(type.networkInfo().networkCards(), hasSize(1));
            NetworkCardInfo card = type.networkInfo().networkCards().getFirst();
            assertEquals(0, card.networkCardIndex());
            assertEquals(maxInterfaces, card.maximumNetworkInterfaces());

            String zone = ec2.describeAvailabilityZones().availabilityZones().getFirst().zoneName();
            List<InstanceTypeOffering> offerings = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.AVAILABILITY_ZONE)
                    .filters(f -> f.name("instance-type").values(name), f -> f.name("location").values(zone)))
                    .instanceTypeOfferings();
            assertThat(offerings, hasSize(1));
            assertEquals(name, offerings.getFirst().instanceTypeAsString());
            assertEquals(zone, offerings.getFirst().location());
            assertEquals(LocationType.AVAILABILITY_ZONE, offerings.getFirst().locationType());
        }
    }
}
