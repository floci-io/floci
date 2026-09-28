package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /**
     * Pins the {@code !spec.hasNetworkConfiguration()} guard in {@code startCreated}: without it,
     * a container with both published ports and a link-local address would be connected twice,
     * once by {@code create}'s pre-start attach and again by {@code startCreated}'s post-start
     * reconnect for port-bound containers (raised in review on #4063).
     */
    @Test
    void createAndStartConnectsOnceForPortsPlusLinkLocalIp() {
        when(dockerClient.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class, RETURNS_SELF);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(dockerClient.inspectContainerCmd("container-id")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getPorts()).thenReturn(null);

        manager().createAndStart(specWithLinkLocalIp(Map.of(8080, 18080)));

        verify(connectCmd, times(1)).exec();
        verify(disconnectCmd, never()).exec();
    }

    /**
     * Docker only honours host, none and container:<id> on the HostConfig at creation, refuses
     * to connect a container to them afterwards, and publishes no host ports through them. A
     * port-bound spec must therefore still put the mode on the HostConfig instead of taking the
     * publish-then-connect path, which left such containers on the default bridge.
     */
    @ParameterizedTest
    @ValueSource(strings = {"host", "none", "container:sibling"})
    void createAppliesNamespaceNetworkModeInsteadOfPublishingPorts(String networkMode) {
        manager().create(spec(networkMode, Map.of(9200, 9400), List.of()));

        HostConfig hostConfig = createdHostConfig();
        assertEquals(networkMode, hostConfig.getNetworkMode());
        assertNull(hostConfig.getPortBindings());
        verify(connectCmd, never()).exec();
    }

    @Test
    void createDoesNotAllocateDynamicPortsOnHostNetwork() {
        manager().create(spec("host", Map.of(9200, 0), List.of()));

        verify(portAllocator, never()).allocateAny();
    }

    @Test
    void createAndStartDoesNotConnectHostNetworkAfterStart() {
        when(dockerClient.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));

        ContainerInfo info = manager().createAndStart(spec("host", Map.of(9200, 9400), List.of()));

        verify(connectCmd, never()).exec();
        assertTrue(info.publishedHostPort(9200).isEmpty());
    }

    @Test
    void endpointsOfHostNetworkContainersPointAtLocalhostWhenFlociRunsInDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(dockerClient.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));
        when(inspectOf("container-id").getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("host"));

        ContainerInfo info = manager().createAndStart(spec("host", Map.of(9200, 9400), List.of(9200)));

        assertEquals(new EndpointInfo("localhost", 9200), info.getEndpoint(9200));
    }

    @Test
    void endpointsOfNamespaceSharingContainersPointAtTheOwningContainerWhenFlociRunsInDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(dockerClient.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));
        when(inspectOf("container-id").getHostConfig())
                .thenReturn(HostConfig.newHostConfig().withNetworkMode("container:sibling-id"));
        InspectContainerResponse owner = inspectOf("sibling-id");
        NetworkSettings ownerNetworks = mock(NetworkSettings.class);
        when(owner.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("bridge"));
        when(owner.getNetworkSettings()).thenReturn(ownerNetworks);
        when(ownerNetworks.getNetworks())
                .thenReturn(Map.of("bridge", new ContainerNetwork().withIpv4Address("172.17.0.7")));

        ContainerInfo info = manager().createAndStart(spec("container:sibling", Map.of(9200, 9400), List.of(9200)));

        assertEquals(new EndpointInfo("172.17.0.7", 9200), info.getEndpoint(9200));
    }

    @Test
    void endpointsOfContainersJoiningAHostNetworkContainerPointAtLocalhostWhenFlociRunsInDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(dockerClient.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));
        when(inspectOf("container-id").getHostConfig())
                .thenReturn(HostConfig.newHostConfig().withNetworkMode("container:sibling-id"));
        when(inspectOf("sibling-id").getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("host"));

        ContainerInfo info = manager().createAndStart(spec("container:sibling", Map.of(9200, 9400), List.of(9200)));

        assertEquals(new EndpointInfo("localhost", 9200), info.getEndpoint(9200));
    }

    private InspectContainerResponse inspectOf(String containerId) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class, RETURNS_SELF);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        return inspect;
    }

    private HostConfig createdHostConfig() {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector,
                portAllocator, config);
    }

    private static ContainerSpec spec(String networkMode, Map<Integer, Integer> portBindings,
                                      List<Integer> exposedPorts) {
        return new ContainerSpec(
                "busybox:stable", "probe", List.of(), null, null, null, portBindings, List.of(),
                exposedPorts, networkMode, List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                false, null, List.of(), null, null, List.of(), List.of(), null, null, false,
                List.of(), Map.of());
    }

    private static ContainerSpec specWithLinkLocalIp(Map<Integer, Integer> portBindings) {
        return new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, portBindings, List.of(),
                List.of(), "test-network", List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                false, null, List.of(), null, null, List.of(), List.of(), null, null, false,
                List.of(LINK_LOCAL_IP), Map.of());
    }
}
