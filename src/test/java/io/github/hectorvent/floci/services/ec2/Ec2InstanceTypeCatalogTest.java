package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class Ec2InstanceTypeCatalogTest {

    @Inject
    Ec2InstanceTypeCatalog instanceTypeCatalog;

    @Test
    void catalogContainsCurrentTypesAndLargeGravitonTypes() {
        Set<String> instanceTypes = instanceTypeCatalog.instanceTypes().stream()
                .map(instanceType -> instanceType.instanceType)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "t2.micro",
                "t3.micro",
                "t3.small",
                "t3.medium",
                "m5.large",
                "t4g.micro",
                "t4g.small",
                "t4g.medium",
                "m6gd.large",
                "m6gd.2xlarge",
                "m7gd.large",
                "m7gd.2xlarge",
                "m8gd.medium",
                "m8gd.large",
                "m8gd.2xlarge"), instanceTypes);
    }

    @Test
    void largeGravitonTypesHaveExactMetadata() {
        assertLargeGravitonType("m6gd.large");
        assertLargeGravitonType("m7gd.large");
        assertLargeGravitonType("m8gd.large");
    }

    @Test
    void unknownInstanceTypeIsAbsent() {
        assertFalse(instanceTypeCatalog.find("m8gd.xlarge").isPresent());
    }

    @Test
    void catalogEntriesIncludeNetworkCompatibilityMetadata() {
        instanceTypeCatalog.instanceTypes().forEach(instanceType ->
                assertNotNull(instanceType.encryptionInTransitSupported, instanceType.instanceType));

        assertFalse(instanceTypeCatalog.find("m5.large").orElseThrow().encryptionInTransitSupported);
        assertFalse(instanceTypeCatalog.find("t4g.medium").orElseThrow().encryptionInTransitSupported);
    }

    @Test
    void catalogEntriesIncludeUsableNetworkCapacityMetadata() {
        instanceTypeCatalog.instanceTypes().forEach(instanceType -> {
            assertNotNull(instanceType.defaultNetworkCardIndex, instanceType.instanceType);
            assertNotNull(instanceType.ipv4AddressesPerInterface, instanceType.instanceType);
            assertNotNull(instanceType.networkCards, instanceType.instanceType);
            assertTrue(instanceType.defaultNetworkCardIndex >= 0);
            assertTrue(instanceType.defaultNetworkCardIndex < instanceType.networkCards.size());
            assertTrue(instanceType.ipv4AddressesPerInterface > 0);
            assertEquals(instanceType.defaultNetworkCardIndex,
                    instanceType.networkCards.get(instanceType.defaultNetworkCardIndex).networkCardIndex);
            assertTrue(instanceType.networkCards.get(instanceType.defaultNetworkCardIndex).maximumNetworkInterfaces > 0);
        });

        Ec2InstanceTypeCatalog.CatalogInstanceType m5Large = instanceTypeCatalog.find("m5.large").orElseThrow();
        assertEquals(0, m5Large.defaultNetworkCardIndex);
        assertEquals(10, m5Large.ipv4AddressesPerInterface);
        assertEquals(3, m5Large.networkCards.get(0).maximumNetworkInterfaces);

        Ec2InstanceTypeCatalog.CatalogInstanceType m8gd2xlarge =
                instanceTypeCatalog.find("m8gd.2xlarge").orElseThrow();
        assertEquals(0, m8gd2xlarge.defaultNetworkCardIndex);
        assertEquals(15, m8gd2xlarge.ipv4AddressesPerInterface);
        assertEquals(4, m8gd2xlarge.networkCards.get(0).maximumNetworkInterfaces);
    }

    @Test
    void catalogRejectsIncompleteNetworkCapacityMetadata() {
        Ec2InstanceTypeCatalog.CatalogInstanceType type = new Ec2InstanceTypeCatalog.CatalogInstanceType();
        type.instanceType = "test.type";
        type.vcpu = 1;
        type.memoryMib = 1024;
        type.supportedArchitectures = List.of("x86_64");
        type.encryptionInTransitSupported = false;
        type.defaultNetworkCardIndex = 0;
        type.ipv4AddressesPerInterface = 10;
        Ec2InstanceTypeCatalog.CatalogNetworkCard card = new Ec2InstanceTypeCatalog.CatalogNetworkCard();
        card.networkCardIndex = 0;
        type.networkCards = List.of(card);

        Ec2InstanceTypeCatalog.Catalog catalog = new Ec2InstanceTypeCatalog.Catalog();
        catalog.instanceTypes = List.of(type);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Ec2InstanceTypeCatalog(catalog));
        assertTrue(error.getMessage().contains("maximumNetworkInterfaces"));
    }

    private void assertLargeGravitonType(String name) {
        Ec2InstanceTypeCatalog.CatalogInstanceType instanceType = instanceTypeCatalog.find(name).orElseThrow();

        assertEquals(2, instanceType.vcpu);
        assertEquals(8192, instanceType.memoryMib);
        assertEquals(118, instanceType.localStorageGiB);
        assertEquals(List.of("arm64"), instanceType.supportedArchitectures);
        assertTrue(instanceType.currentGeneration);
    }
}
