package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.DeviceRequest;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts that a spec's device requests reach Docker's HostConfig unchanged, and that a
 * container which asked for nothing still asks for nothing. The second half is the one
 * that matters operationally: on a host whose daemon defaults to an accelerator runtime,
 * silently granting devices to every container Floci launches would be invisible until
 * something contended for the hardware.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContainerLifecycleManager: device requests")
class ContainerLifecycleManagerDeviceRequestTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    EmulatorConfig.TlsConfig tlsConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(imageCacheService.ensureImageExists(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void gpuCountRequestReachesHostConfig() {
        CreateContainerCmd createCmd = stubCreateContainer();
        DeviceRequest request = new DeviceRequest()
                .withCount(1)
                .withCapabilities(List.of(List.of("gpu")));

        manager().create(specWithDeviceRequests(List.of(request)));

        assertEquals(List.of(request), capturedHostConfig(createCmd).getDeviceRequests());
    }

    @Test
    void cdiDeviceRequestReachesHostConfig() {
        CreateContainerCmd createCmd = stubCreateContainer();
        DeviceRequest request = new DeviceRequest()
                .withDriver("cdi")
                .withDeviceIds(List.of("nvidia.com/gpu=GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"));

        manager().create(specWithDeviceRequests(List.of(request)));

        List<DeviceRequest> applied = capturedHostConfig(createCmd).getDeviceRequests();
        assertEquals(1, applied.size());
        assertEquals("cdi", applied.get(0).getDriver());
        assertEquals(
                List.of("nvidia.com/gpu=GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"),
                applied.get(0).getDeviceIds());
    }

    @Test
    void containerThatAskedForNoDeviceGetsNone() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        assertNull(capturedHostConfig(createCmd).getDeviceRequests());
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }

    private static ContainerSpec specWithDeviceRequests(List<DeviceRequest> deviceRequests) {
        return new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, Map.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null,
                null, List.of(), deviceRequests);
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private static HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}
