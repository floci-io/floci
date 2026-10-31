package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.LocationType;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class Ec2R5CatalogIntegrationTest {

    @Test
    void r5LargeMetadataAndAvailabilityZoneOfferingsAgree() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            assertThat(ec2.describeInstanceTypes(r -> r.instanceTypes(InstanceType.R5_LARGE))
                    .instanceTypes()).singleElement().satisfies(type -> {
                        assertThat(type.instanceType()).isEqualTo(InstanceType.R5_LARGE);
                        assertThat(type.vCpuInfo().defaultVCpus()).isEqualTo(2);
                        assertThat(type.memoryInfo().sizeInMiB()).isEqualTo(16384L);
                        assertThat(type.processorInfo().supportedArchitecturesAsStrings())
                                .containsExactly("x86_64");
                        assertThat(type.instanceStorageSupported()).isFalse();
                        assertThat(type.burstablePerformanceSupported()).isFalse();
                        assertThat(type.supportedUsageClassesAsStrings())
                                .containsExactlyInAnyOrder("on-demand", "spot");
                        assertThat(type.networkInfo().encryptionInTransitSupported()).isFalse();
                        assertThat(type.networkInfo().defaultNetworkCardIndex()).isZero();
                        assertThat(type.networkInfo().ipv4AddressesPerInterface()).isEqualTo(10);
                        assertThat(type.networkInfo().networkCards()).singleElement().satisfies(card -> {
                            assertThat(card.networkCardIndex()).isZero();
                            assertThat(card.maximumNetworkInterfaces()).isEqualTo(3);
                        });
                    });

            String zone = ec2.describeAvailabilityZones().availabilityZones().get(0).zoneName();
            assertThat(ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.AVAILABILITY_ZONE)
                    .filters(
                            f -> f.name("instance-type").values("r5.large"),
                            f -> f.name("location").values(zone)))
                    .instanceTypeOfferings()).singleElement().satisfies(offering -> {
                        assertThat(offering.instanceType()).isEqualTo(InstanceType.R5_LARGE);
                        assertThat(offering.locationType()).isEqualTo(LocationType.AVAILABILITY_ZONE);
                        assertThat(offering.location()).isEqualTo(zone);
                    });
        }
    }
}
