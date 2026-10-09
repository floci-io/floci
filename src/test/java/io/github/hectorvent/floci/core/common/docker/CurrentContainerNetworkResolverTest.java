package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentContainerNetworkResolverTest {

    @Test
    void resolveNetworkName_prefersUserDefinedNetwork() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        Map<String, ContainerNetwork> networks = networks(
                "bridge", "172.17.0.2",
                "avoxx-network", "172.24.0.2");
        when(dockerClient.inspectContainerCmd("floci-container")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(networks);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertEquals(Optional.of("avoxx-network"), resolver.resolveNetworkName());
        assertEquals(Optional.of("172.24.0.2"), resolver.resolveContainerIp());
    }

    @Test
    void resolveNetworkName_returnsEmptyOutsideContainer() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertTrue(resolver.resolveNetworkName().isEmpty());
    }

    @Test
    void resolveContainerIdReturnsCurrentContainerWhenRunningInDocker() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertEquals(Optional.of("floci-container"), resolver.resolveContainerId());
    }

    @Test
    void resolveContainerIdReturnsEmptyOutsideDocker() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertTrue(resolver.resolveContainerId().isEmpty());
    }

    @Test
    void resolvePublishedPort_skipsUnboundEntryAndCachesResult() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        Ports ports = new Ports();
        ports.getBindings().put(ExposedPort.tcp(7000), new Ports.Binding[] {
                null,
                Ports.Binding.bindPort(49173)
        });
        when(dockerClient.inspectContainerCmd("floci-container")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings().getPorts()).thenReturn(ports);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertEquals(OptionalInt.of(49173), resolver.resolvePublishedPort(7000));

        ports.getBindings().put(ExposedPort.tcp(7000), new Ports.Binding[] {Ports.Binding.bindPort(49174)});
        assertEquals(OptionalInt.of(49173), resolver.resolvePublishedPort(7000));
    }

    @Test
    void resolvePublishedPort_portNotPublished_cachesTheEmptyResult() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        // EXPOSE 4566 without -p: the port is listed with no host binding.
        Ports ports = new Ports();
        ports.getBindings().put(ExposedPort.tcp(4566), null);
        InspectContainerCmd inspectCmd = inspectReturning(dockerClient, ports);

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertTrue(resolver.resolvePublishedPort(4566).isEmpty());
        assertTrue(resolver.resolvePublishedPort(4566).isEmpty());
        verify(inspectCmd, times(1)).exec();
    }

    @Test
    void resolvePublishedPort_inspectFails_cachesTheEmptyResult() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("floci-container")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("Docker daemon unreachable"));

        CurrentContainerNetworkResolver resolver =
                new TestResolver(dockerClient, containerDetector, "floci-container");

        assertTrue(resolver.resolvePublishedPort(4566).isEmpty());
        assertTrue(resolver.resolvePublishedPort(4566).isEmpty());
        verify(inspectCmd, times(1)).exec();
    }

    private static InspectContainerCmd inspectReturning(DockerClient dockerClient, Ports ports) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(dockerClient.inspectContainerCmd("floci-container")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings().getPorts()).thenReturn(ports);
        return inspectCmd;
    }

    private static Map<String, ContainerNetwork> networks(String firstName, String firstIp,
                                                          String secondName, String secondIp) {
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put(firstName, network(firstIp));
        networks.put(secondName, network(secondIp));
        return networks;
    }

    private static ContainerNetwork network(String ip) {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(ip);
        return network;
    }

    private static class TestResolver extends CurrentContainerNetworkResolver {
        private final String containerId;

        TestResolver(DockerClient dockerClient, ContainerDetector containerDetector, String containerId) {
            super(dockerClient, containerDetector);
            this.containerId = containerId;
        }

        @Override
        String currentContainerId() {
            return containerId;
        }
    }
}
