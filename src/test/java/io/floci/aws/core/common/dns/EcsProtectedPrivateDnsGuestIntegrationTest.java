package io.floci.aws.core.common.dns;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

@QuarkusTest
@TestProfile(EcsProtectedPrivateDnsGuestIntegrationTest.ProtectedGuestProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EcsProtectedPrivateDnsGuestIntegrationTest extends EcsPrivateDnsGuestIntegrationTest {

    public static final class ProtectedGuestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ecs.shared-task-network", "true",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.services.ec2.vpc-networks.enabled", "false",
                    "floci.docker.resource-namespace", "ecs-protected-private-dns-guest-it");
        }
    }

    @Override
    boolean usesSharedNamespace() {
        return true;
    }
}
