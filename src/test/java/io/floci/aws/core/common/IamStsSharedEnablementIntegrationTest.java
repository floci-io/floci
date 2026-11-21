package io.floci.aws.core.common;

import io.floci.aws.testing.PerServicePersistentStorageProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Turning IAM off turns STS off too. {@link PerServicePersistentStorageProfile} turns IAM off. */
@QuarkusTest
@TestProfile(PerServicePersistentStorageProfile.class)
class IamStsSharedEnablementIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @Test
    void disablingIamAlsoDisablesSts() {
        assertFalse(serviceRegistry.isServiceEnabled("iam"));
        assertFalse(serviceRegistry.isServiceEnabled("sts"));
    }
}
