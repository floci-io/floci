package io.github.hectorvent.floci.services.eks;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EksService is created lazily, and creating it is what restarts each persisted cluster's k3s
 * container. Boot has to create it, or persisted clusters stay down after a restart until the
 * first EKS request. The profile of its own gives this test a fresh application that no other
 * test has sent an EKS request to, with EKS out of mock mode as boot restores only real clusters.
 */
@QuarkusTest
@TestProfile(EksBootRestoreIntegrationTest.FreshBootProfile.class)
class EksBootRestoreIntegrationTest {

    public static final class FreshBootProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false");
        }
    }

    @Test
    void bootCreatesEksServiceWithoutAnEksRequest() {
        Map<InjectableBean<?>, Object> created = Arc.container().getActiveContext(ApplicationScoped.class)
                .getState().getContextualInstances();

        assertTrue(created.keySet().stream().anyMatch(bean -> bean.getBeanClass() == EksService.class),
                "EksService must be created at boot, not on the first EKS request");
    }
}
