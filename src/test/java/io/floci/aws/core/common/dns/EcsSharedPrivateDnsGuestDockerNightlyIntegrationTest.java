package io.floci.aws.core.common.dns;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

@QuarkusTest
@TestProfile(EcsSharedPrivateDnsGuestDockerNightlyIntegrationTest.SharedGuestProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EcsSharedPrivateDnsGuestDockerNightlyIntegrationTest extends EcsPrivateDnsGuestDockerNightlyIntegrationTest {

    public static final class SharedGuestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ecs.shared-task-network", "true",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ec2.vpc-networks.enabled", "false",
                    "floci.docker.resource-namespace", "ecs-shared-private-dns-guest-it");
        }
    }

    @Override
    boolean usesSharedNamespace() {
        return true;
    }
}
