package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.VolumesFrom;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerVolumesFromTest {

    @Test
    void createPassesInheritedVolumesToDockerHostConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        ContainerSpec spec = builder.newContainer("app:latest")
                .withVolumesFrom("readonly-source-id", true)
                .withVolumesFrom("readwrite-source-id", false)
                .build();

        HostConfig hostConfig = createHostConfig(config, spec);
        VolumesFrom[] inherited = hostConfig.getVolumesFrom();
        assertEquals("readonly-source-id", inherited[0].getContainer());
        assertEquals(AccessMode.ro, inherited[0].getAccessMode());
        assertEquals("readwrite-source-id", inherited[1].getContainer());
        assertEquals(AccessMode.rw, inherited[1].getAccessMode());
    }

    @Test
    void createPreservesInheritedVolumesWhenSharingRouterNetworkAndOmitsExtraHosts() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        ContainerSpec spec = builder.newContainer("app:latest")
                .withVolumesFrom("source-id", true)
                .withNetworkMode("container:router-id")
                .withExtraHost("host.docker.internal", "host-gateway")
                .build();

        HostConfig hostConfig = createHostConfig(config, spec);

        assertEquals("container:router-id", hostConfig.getNetworkMode());
        assertEquals(1, hostConfig.getVolumesFrom().length);
        assertEquals("source-id", hostConfig.getVolumesFrom()[0].getContainer());
        assertEquals(AccessMode.ro, hostConfig.getVolumesFrom()[0].getAccessMode());
        assertTrue(hostConfig.getExtraHosts() == null || hostConfig.getExtraHosts().length == 0);
    }

    private static HostConfig createHostConfig(EmulatorConfig config, ContainerSpec spec) {
        DockerClient dockerClient = mock(DockerClient.class);
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
}
