package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerNetworkReachabilityTest {

    /** An address in the documentation-only TEST-NET-1 range: reachable from nowhere. */
    private static final String UNROUTABLE_IP = "192.0.2.1";

    private static ContainerNetworkReachability reachability(Optional<Boolean> override,
                                                             boolean flociInContainer,
                                                             String dockerOperatingSystem) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().containerIpsRoutable()).thenReturn(override);

        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(flociInContainer);

        DockerClient dockerClient = mock(DockerClient.class);
        InfoCmd infoCmd = mock(InfoCmd.class);
        Info info = mock(Info.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        when(infoCmd.exec()).thenReturn(info);
        when(info.getOperatingSystem()).thenReturn(dockerOperatingSystem);

        return new ContainerNetworkReachability(config, detector, dockerClient);
    }

    @Test
    void configuredValueWinsWithoutProbing() {
        DockerClient dockerClient = mock(DockerClient.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().containerIpsRoutable()).thenReturn(Optional.of(true));
        ContainerDetector detector = mock(ContainerDetector.class);

        ContainerNetworkReachability reachability =
                new ContainerNetworkReachability(config, detector, dockerClient);

        assertTrue(reachability.isContainerIpRoutable(UNROUTABLE_IP));
        verify(dockerClient, never()).infoCmd();
        verify(detector, never()).isRunningInContainer();
    }

    @Test
    void configuredFalseOverridesAProbeThatWouldSucceed() {
        ContainerNetworkReachability reachability =
                reachability(Optional.of(false), false, "OrbStack");

        assertFalse(reachability.isContainerIpRoutable("127.0.0.1"));
    }

    @Test
    void refusedConnectionCountsAsRoutable() {
        // Nothing listens on 127.0.0.1:22 in CI either way; what matters is that the loopback
        // stack answers with a RST rather than swallowing the packet.
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), false, "OrbStack");

        assertTrue(reachability.isContainerIpRoutable("127.0.0.1"));
    }

    @Test
    void unreachableAddressCountsAsUnroutable() {
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), false, "OrbStack");
        int previousTimeout = ContainerNetworkReachability.probeTimeoutMillis;
        ContainerNetworkReachability.probeTimeoutMillis = 250;
        try {
            assertFalse(reachability.isContainerIpRoutable(UNROUTABLE_IP));
        } finally {
            ContainerNetworkReachability.probeTimeoutMillis = previousTimeout;
        }
    }

    @Test
    void containerisedFlociOnDockerDesktopDoesNotTrustItsOwnProbe() {
        // The probe succeeds container-to-container on Docker Desktop too, but the host — where
        // Terraform and Terratest run — still cannot route there.
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), true, "Docker Desktop");

        assertFalse(reachability.isContainerIpRoutable("127.0.0.1"));
    }

    @Test
    void containerisedFlociOnALinuxBackendTrustsItsProbe() {
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), true, "Ubuntu 24.04.1 LTS");

        assertTrue(reachability.isContainerIpRoutable("127.0.0.1"));
    }

    @Test
    void answerIsMemoisedAcrossInstances() {
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), false, "OrbStack");

        assertTrue(reachability.isContainerIpRoutable("127.0.0.1"));
        // A later, unreachable target does not re-open the question.
        assertTrue(reachability.isContainerIpRoutable(UNROUTABLE_IP));

        reachability.forget();
        ContainerNetworkReachability.probeTimeoutMillis = 250;
        try {
            assertFalse(reachability.isContainerIpRoutable(UNROUTABLE_IP));
        } finally {
            ContainerNetworkReachability.probeTimeoutMillis = 1500;
        }
    }

    @Test
    void missingContainerIpIsNotRoutable() {
        ContainerNetworkReachability reachability =
                reachability(Optional.empty(), false, "OrbStack");

        assertFalse(reachability.isContainerIpRoutable(null));
        assertFalse(reachability.isContainerIpRoutable("  "));
    }
}
