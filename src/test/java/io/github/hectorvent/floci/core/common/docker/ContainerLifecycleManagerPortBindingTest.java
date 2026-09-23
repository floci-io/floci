package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins which host interface a published port lands on. A port bound to every interface is
 * reachable by anyone who can reach the host, so the difference between a null host IP and an
 * explicit one is a security boundary rather than a formatting detail.
 */
class ContainerLifecycleManagerPortBindingTest {

    @Test
    void aPlainPortBindingPublishesOnEveryInterface() {
        Ports.Binding binding = bindingFor(builder -> builder.withPortBinding(8080, 18_080), 8080);

        assertNull(binding.getHostIp(), "Docker's own default is every interface");
        assertEquals("18080", binding.getHostPortSpec());
    }

    @Test
    void aLoopbackPortBindingPublishesOnLoopbackOnly() {
        Ports.Binding binding = bindingFor(
                builder -> builder.withLoopbackPortBinding(5050, 5050), 5050);

        assertEquals("127.0.0.1", binding.getHostIp());
        assertEquals("5050", binding.getHostPortSpec());
    }

    @Test
    void anExplicitHostIpReachesTheBinding() {
        Ports.Binding binding = bindingFor(
                builder -> builder.withPortBinding(4500, 4500, "127.0.0.1"), 4500);

        assertEquals("127.0.0.1", binding.getHostIp());
        assertEquals("4500", binding.getHostPortSpec());
    }

    @Test
    void anExplicitWildcardHostIpIsPassedThroughAsAsked() {
        Ports.Binding binding = bindingFor(
                builder -> builder.withPortBinding(4500, 4500, "0.0.0.0"), 4500);

        assertEquals("0.0.0.0", binding.getHostIp());
    }

    @Test
    void aBlankHostIpFallsBackToDockersOwnDefault() {
        Ports.Binding binding = bindingFor(
                builder -> builder.withPortBinding(4500, 4500, "  "), 4500);

        assertNull(binding.getHostIp());
    }

    @Test
    void oneBoundInterfaceDoesNotLeakOntoTheOtherPorts() {
        ContainerSpec spec = specFor(builder -> builder
                .withPortBinding(4500, 4500, "127.0.0.1")
                .withPortBinding(9000, 9000));
        Ports ports = portsOf(spec);

        assertEquals("127.0.0.1", only(ports, 4500).getHostIp());
        assertNull(only(ports, 9000).getHostIp());
    }

    private static Ports.Binding bindingFor(UnaryOperator<ContainerBuilder.Builder> customizer,
                                            int containerPort) {
        return only(portsOf(specFor(customizer)), containerPort);
    }

    private static Ports.Binding only(Ports ports, int containerPort) {
        Ports.Binding[] bindings = ports.getBindings().get(ExposedPort.tcp(containerPort));
        assertEquals(1, bindings.length, "expected exactly one binding for port " + containerPort);
        return bindings[0];
    }

    private static ContainerSpec specFor(UnaryOperator<ContainerBuilder.Builder> customizer) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        return customizer.apply(builder.newContainer("app:latest")).build();
    }

    private static Ports portsOf(ContainerSpec spec) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("app:latest")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("app-id");
        when(createCmd.exec()).thenReturn(response);
        ImageCacheService imageCacheService = mock(ImageCacheService.class);
        when(imageCacheService.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PortAllocator portAllocator = mock(PortAllocator.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
                dockerClient, imageCacheService, mock(ContainerDetector.class), portAllocator, config);

        manager.create(spec);

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue().getPortBindings();
    }
}
