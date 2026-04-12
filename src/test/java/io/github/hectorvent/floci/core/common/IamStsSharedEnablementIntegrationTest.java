package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(IamStsSharedEnablementIntegrationTest.IamDisabledProfile.class)
class IamStsSharedEnablementIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @Test
    void disablingIamAlsoDisablesSts() {
        assertFalse(serviceRegistry.isServiceEnabled("iam"));
        assertFalse(serviceRegistry.isServiceEnabled("sts"));
    }

    public static final class IamDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enabled", "false");
        }
    }
}
