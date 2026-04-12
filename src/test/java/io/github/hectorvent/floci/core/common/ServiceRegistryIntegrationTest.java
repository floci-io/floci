package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ServiceRegistryIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @Test
    void enabledServicesIncludeEc2AndEcs() {
        assertTrue(serviceRegistry.getEnabledServices().contains("ec2"));
        assertTrue(serviceRegistry.getEnabledServices().contains("ecs"));
    }

    @Test
    void unknownServicesDefaultToEnabled() {
        assertTrue(serviceRegistry.isServiceEnabled("totally-unknown-service"));
    }
}
