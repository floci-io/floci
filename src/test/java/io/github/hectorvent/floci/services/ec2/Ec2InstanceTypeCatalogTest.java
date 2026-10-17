package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "t2.nano",
                "t2.micro",
                "t2.small",
                "t2.medium",
                "t2.large",
                "t2.xlarge",
                "t2.2xlarge",
                "t3.nano",
                "t3.micro",
                "t3.small",
                "t3.medium",
                "t3a.nano",
                "m5.large",
                "t4g.micro",
                "t4g.small",
                "t4g.medium",
                "m6g.large",
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
    void largerT2TypesResolveWithDocumentedCapacity() {
        assertT2Type("t2.medium", 2, 4096, 6, 3);
        assertT2Type("t2.large", 2, 8192, 12, 3);
        assertT2Type("t2.xlarge", 4, 16384, 15, 3);
        assertT2Type("t2.2xlarge", 8, 32768, 15, 3);
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

    @Test
    void t2FamilySupportsI386AndX86_64() {
        for (String name : List.of("t2.nano", "t2.micro", "t2.small", "t2.medium")) {
            assertEquals(Set.of("i386", "x86_64"),
                    Set.copyOf(instanceTypeCatalog.find(name).orElseThrow().supportedArchitectures), name);
        }
        // AWS's 32-bit-capable t2 list stops at medium
        for (String name : List.of("t2.large", "t2.xlarge", "t2.2xlarge")) {
            assertEquals(List.of("x86_64"), instanceTypeCatalog.find(name).orElseThrow().supportedArchitectures, name);
        }
        // launch-time default architecture must stay x86_64 (first entry)
        assertEquals("x86_64", instanceTypeCatalog.find("t2.micro").orElseThrow().supportedArchitectures.get(0));
    }

    @Test
    void burstableFamiliesCarryTheirDocumentedDefaultCreditOption() {
        assertEquals(Optional.of("standard"), Ec2InstanceTypeCatalog.defaultCpuCredits("t2.micro"));
        assertEquals(Optional.of("unlimited"), Ec2InstanceTypeCatalog.defaultCpuCredits("t3.small"));
        assertEquals(Optional.of("unlimited"), Ec2InstanceTypeCatalog.defaultCpuCredits("t3a.large"));
        assertEquals(Optional.of("unlimited"), Ec2InstanceTypeCatalog.defaultCpuCredits("t4g.medium"));
    }

    @Test
    void nonBurstableFamiliesHaveNoCreditOption() {
        assertEquals(Optional.empty(), Ec2InstanceTypeCatalog.defaultCpuCredits("m5.large"));
        // t1 is the one T family that predates the credit model.
        assertEquals(Optional.empty(), Ec2InstanceTypeCatalog.defaultCpuCredits("t1.micro"));
        assertEquals(Optional.empty(), Ec2InstanceTypeCatalog.defaultCpuCredits(null));
        assertFalse(Ec2InstanceTypeCatalog.isBurstablePerformanceType("m5.large"));
        assertTrue(Ec2InstanceTypeCatalog.isBurstablePerformanceType("t3.micro"));
    }

    @Test
    void responseMapReportsBurstablePerformanceSupport() {
        assertEquals(true, instanceTypeCatalog.find("t4g.medium").orElseThrow()
                .toResponseMap().get("burstablePerformanceSupported"));
        assertEquals(false, instanceTypeCatalog.find("m5.large").orElseThrow()
                .toResponseMap().get("burstablePerformanceSupported"));
    }

    private void assertT2Type(String name, int vcpu, int memoryMib, int ipv4PerInterface, int maxInterfaces) {
        Ec2InstanceTypeCatalog.CatalogInstanceType instanceType = instanceTypeCatalog.find(name).orElseThrow();

        assertEquals(vcpu, instanceType.vcpu, name);
        assertEquals(memoryMib, instanceType.memoryMib, name);
        assertEquals(ipv4PerInterface, instanceType.ipv4AddressesPerInterface, name);
        assertEquals(maxInterfaces, instanceType.networkCards.get(0).maximumNetworkInterfaces, name);
        assertTrue(instanceType.currentGeneration, name);
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
