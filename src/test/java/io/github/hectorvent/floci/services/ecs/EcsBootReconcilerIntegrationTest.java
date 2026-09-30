package io.github.hectorvent.floci.services.ecs;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EcsService is created lazily, and creating it is what loads the ECS stores and schedules the
 * reconciler. Boot has to create it, or persisted services stay without tasks after a restart
 * until the first ECS request. The profile of its own gives this test a fresh application that
 * no other test has sent an ECS request to.
 */
@QuarkusTest
@TestProfile(EcsBootReconcilerIntegrationTest.FreshBootProfile.class)
class EcsBootReconcilerIntegrationTest {

    public static final class FreshBootProfile implements QuarkusTestProfile {
    }

    @Test
    void bootStartsTheReconcilerWithoutAnEcsRequest() {
        Map<InjectableBean<?>, Object> created = Arc.container().getActiveContext(ApplicationScoped.class)
                .getState().getContextualInstances();
        EcsService ecsService = created.entrySet().stream()
                .filter(entry -> entry.getKey().getBeanClass() == EcsService.class)
                .map(entry -> (EcsService) entry.getValue())
                .findFirst()
                .orElse(null);

        assertNotNull(ecsService, "EcsService must be created at boot, not on the first ECS request");
        assertFalse(ecsService.isReconcilerShutdown());
    }
}
