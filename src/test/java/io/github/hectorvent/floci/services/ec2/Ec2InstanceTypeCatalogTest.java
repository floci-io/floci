package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private void assertLargeGravitonType(String name) {
        Ec2InstanceTypeCatalog.CatalogInstanceType instanceType = instanceTypeCatalog.find(name).orElseThrow();

        assertEquals(2, instanceType.vcpu);
        assertEquals(8192, instanceType.memoryMib);
        assertEquals(118, instanceType.localStorageGiB);
        assertEquals(List.of("arm64"), instanceType.supportedArchitectures);
        assertTrue(instanceType.currentGeneration);
    }
}
