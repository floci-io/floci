package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerNetworkTest {

    @Test
    void createReconnectsLinkLocalEndpointBeforeReturningContainer() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        DisconnectFromNetworkCmd disconnectCmd = mock(DisconnectFromNetworkCmd.class, RETURNS_SELF);
        ConnectToNetworkCmd connectCmd = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(response);
        when(response.getId()).thenReturn("container-id");
        when(dockerClient.disconnectFromNetworkCmd()).thenReturn(disconnectCmd);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connectCmd);

        ContainerLifecycleManager manager = new ContainerLifecycleManager(
                dockerClient,
                mock(ImageCacheService.class),
                mock(ContainerDetector.class),
                mock(PortAllocator.class),
                mock(EmulatorConfig.class));
        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, Map.of(), List.of(),
                "chris-dev-cloud", List.of(), List.of(), List.of(), Map.of(), null, false,
                null, List.of(), null, null, List.of(), List.of("169.254.170.31"));

        manager.create(spec);

        InOrder order = inOrder(createCmd, disconnectCmd, connectCmd);
        order.verify(createCmd).exec();
        order.verify(disconnectCmd).exec();
        order.verify(connectCmd).exec();
        ArgumentCaptor<ContainerNetwork> endpoint = ArgumentCaptor.forClass(ContainerNetwork.class);
        verify(connectCmd).withContainerNetwork(endpoint.capture());
        String serialized = new ObjectMapper().writeValueAsString(endpoint.getValue());
        assertTrue(serialized.contains("\"LinkLocalIPs\":[\"169.254.170.31\"]"), serialized);
    }
}
