package io.floci.aws.core.common;

import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EC2 Instance Connect has its own enabled flag; turning it off leaves EC2 on.
 * {@link SidecarContainersProfile} turns it off.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class InstanceConnectEnablementIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @Test
    void ownFlagDisablesOnlyInstanceConnect() {
        assertFalse(serviceRegistry.isServiceEnabled("ec2-instance-connect"));
        assertTrue(serviceRegistry.isServiceEnabled("ec2"));
    }
}
