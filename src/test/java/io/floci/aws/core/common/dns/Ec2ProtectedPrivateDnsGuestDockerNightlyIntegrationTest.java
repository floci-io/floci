package io.floci.aws.core.common.dns;

import io.floci.aws.services.ec2.model.Instance;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Ec2ProtectedPrivateDnsGuestDockerNightlyIntegrationTest.ProtectedGuestProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class Ec2ProtectedPrivateDnsGuestDockerNightlyIntegrationTest extends Ec2PrivateDnsGuestDockerNightlyIntegrationTest {

    public static final class ProtectedGuestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.services.ec2.vpc-networks.enabled", "false",
                    "floci.docker.resource-namespace", "ec2-protected-private-dns-guest-it");
        }
    }

    @Override
    List<String> transportAddresses(Instance instance) {
        String mode = docker.inspectContainerCmd(instance.getDockerContainerId()).exec()
                .getHostConfig().getNetworkMode();
        assertTrue(mode.startsWith("container:"), "Guest must use its protected network namespace");
        List<String> addresses = docker.inspectContainerCmd(mode.substring("container:".length())).exec()
                .getNetworkSettings().getNetworks().values().stream()
                .map(network -> network.getIpAddress()).filter(ip -> ip != null && !ip.isBlank()).toList();
        assertFalse(addresses.isEmpty(), "Protected helper must own a transport address");
        return addresses;
    }
}
