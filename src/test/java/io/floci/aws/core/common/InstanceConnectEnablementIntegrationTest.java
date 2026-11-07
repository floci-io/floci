package io.floci.aws.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EC2 Instance Connect has its own enabled flag; turning it off leaves EC2 on. */
@QuarkusTest
@TestProfile(InstanceConnectEnablementIntegrationTest.InstanceConnectDisabledProfile.class)
class InstanceConnectEnablementIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @Test
    void ownFlagDisablesOnlyInstanceConnect() {
        assertFalse(serviceRegistry.isServiceEnabled("ec2-instance-connect"));
        assertTrue(serviceRegistry.isServiceEnabled("ec2"));
    }

    public static final class InstanceConnectDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2instanceconnect.enabled", "false");
        }
    }
}
