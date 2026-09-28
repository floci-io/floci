package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the CPU mapping a container spec carries into Docker's HostConfig: ECS CPU units
 * become a nanoCPU quota, a container-level cpu stays a relative weight, and a quota larger
 * than the host is held to what the daemon will accept rather than failing the launch.
 */
class ContainerLifecycleManagerCpuLimitTest {

    private static final int EIGHT_CPU_HOST = 8;

    @Test
    void cpuUnitsBecomeANanoCpuQuota() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuUnits(512));

        assertEquals(500_000_000L, hostConfig.getNanoCPUs());
    }

    @Test
    void zeroCpuUnitsLeaveTheQuotaOff() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuUnits(0));

        assertNull(hostConfig.getNanoCPUs());
    }

    @Test
    void negativeCpuUnitsLeaveTheQuotaOff() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuUnits(-512));

        assertNull(hostConfig.getNanoCPUs());
    }

    @Test
    void cpuSharesPassThroughAsARelativeWeight() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuShares(256));

        assertEquals(256, hostConfig.getCpuShares());
        assertNull(hostConfig.getNanoCPUs());
    }

    @Test
    void readonlyRootfsReachesHostConfig() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                ContainerBuilder.Builder::withReadonlyRootfs);

        assertTrue(hostConfig.getReadonlyRootfs());
    }

    @Test
    void containerThatAskedForNoCpuLimitGetsNone() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST, UnaryOperator.identity());

        assertNull(hostConfig.getNanoCPUs());
        assertNull(hostConfig.getCpuShares());
        assertNull(hostConfig.getReadonlyRootfs());
    }

    /**
     * A 16 vCPU Fargate task is a legal ECS size but Docker refuses a quota above the host's
     * CPU count, so asking for it verbatim would fail the launch on any smaller machine.
     */
    @Test
    void quotaAboveTheHostCpuCountIsClampedToTheDaemonsCpus() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuUnits(16 * 1024));

        assertEquals(8_000_000_000L, hostConfig.getNanoCPUs());
    }

    @Test
    void quotaAtTheHostCpuCountIsNotClamped() {
        HostConfig hostConfig = createHostConfig(EIGHT_CPU_HOST,
                builder -> builder.withCpuUnits(8 * 1024));

        assertEquals(8_000_000_000L, hostConfig.getNanoCPUs());
    }

    /** A daemon that does not report a CPU count is no reason to rewrite the caller's quota. */
    @Test
    void quotaIsLeftAloneWhenTheDaemonReportsNoCpuCount() {
        HostConfig hostConfig = createHostConfig(null,
                builder -> builder.withCpuUnits(16 * 1024));

        assertEquals(16_000_000_000L, hostConfig.getNanoCPUs());
    }

    private static HostConfig createHostConfig(Integer hostCpus,
                                               UnaryOperator<ContainerBuilder.Builder> customizer) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        ContainerSpec spec = customizer.apply(builder.newContainer("app:latest")).build();

        DockerClient dockerClient = mock(DockerClient.class);
        stubDaemonCpuCount(dockerClient, hostCpus);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("app:latest")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("app-id");
        when(createCmd.exec()).thenReturn(response);
        ImageCacheService imageCacheService = mock(ImageCacheService.class);
        when(imageCacheService.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
                dockerClient, imageCacheService, mock(ContainerDetector.class), mock(PortAllocator.class), config);

        manager.create(spec);

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }

    private static void stubDaemonCpuCount(DockerClient dockerClient, Integer hostCpus) {
        InfoCmd infoCmd = mock(InfoCmd.class);
        Info info = mock(Info.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        when(infoCmd.exec()).thenReturn(info);
        when(info.getNCPU()).thenReturn(hostCpus);
    }
}
