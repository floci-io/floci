package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerNetworkTest {

    private static final String LINK_LOCAL_IP = "169.254.170.31";

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

    private CreateContainerCmd createCmd;
    private DisconnectFromNetworkCmd disconnectCmd;
    private ConnectToNetworkCmd connectCmd;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(imageCacheService.ensureImageExists(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        disconnectCmd = mock(DisconnectFromNetworkCmd.class, RETURNS_SELF);
        connectCmd = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        lenient().when(createCmd.exec()).thenReturn(response);
        lenient().when(response.getId()).thenReturn("container-id");
        lenient().when(dockerClient.disconnectFromNetworkCmd()).thenReturn(disconnectCmd);
        lenient().when(dockerClient.connectToNetworkCmd()).thenReturn(connectCmd);
    }

    @Test
    void createReconnectsLinkLocalEndpointBeforeReturningContainer() throws Exception {
        manager().create(specWithLinkLocalIp(Map.of()));

        InOrder order = inOrder(createCmd, disconnectCmd, connectCmd);
        order.verify(createCmd).exec();
        order.verify(disconnectCmd).exec();
        order.verify(connectCmd).exec();
        ArgumentCaptor<ContainerNetwork> endpoint = ArgumentCaptor.forClass(ContainerNetwork.class);
        verify(connectCmd).withContainerNetwork(endpoint.capture());
        String serialized = new ObjectMapper().writeValueAsString(endpoint.getValue());
        assertTrue(serialized.contains("\"LinkLocalIPs\":[\"" + LINK_LOCAL_IP + "\"]"), serialized);
    }

    @Test
    void createKeepsPublishedPortsByConnectingWithoutDisconnecting() {
        manager().create(specWithLinkLocalIp(Map.of(8080, 18080)));

        verify(disconnectCmd, never()).exec();
        verify(connectCmd).exec();
    }

    @Test
    void createRemovesContainerWhenNetworkAttachFails() {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.removeContainerCmd("container-id")).thenReturn(removeCmd);
        doThrow(new IllegalStateException("network gone")).when(connectCmd).exec();

        assertThrows(IllegalStateException.class, () -> manager().create(specWithLinkLocalIp(Map.of())));

        verify(dockerClient).removeContainerCmd("container-id");
    }

    @Test
    void createLeavesNetworkAloneWithoutLinkLocalIps() {
        manager().create(new ContainerSpec("busybox:stable"));

        verify(disconnectCmd, never()).exec();
        verify(connectCmd, never()).exec();
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector,
                portAllocator, config);
    }

    private static ContainerSpec specWithLinkLocalIp(Map<Integer, Integer> portBindings) {
        return new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, portBindings, List.of(),
                List.of(), "test-network", List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                false, null, List.of(), null, null, List.of(), List.of(), null, null, false,
                List.of(LINK_LOCAL_IP));
    }
}
