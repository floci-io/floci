package io.github.hectorvent.floci.services.pipes;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KarapaceManagerTest {

    private static final int REST_PORT = 8082;

    @Mock private ContainerLifecycleManager lifecycleManager;
    @Mock private ContainerDetector containerDetector;
    @Mock private PortAllocator portAllocator;
    @Mock private EmulatorConfig config;
    @Mock private DockerHostResolver dockerHostResolver;
    @Mock private EmbeddedDnsServer embeddedDnsServer;

    private KarapaceManager manager;
    private HttpServer fakeRestServer;

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.PipesServiceConfig pipes = mock(EmulatorConfig.PipesServiceConfig.class);
        EmulatorConfig.KafkaRestBridgeConfig bridge = mock(EmulatorConfig.KafkaRestBridgeConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);

        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.pipes()).thenReturn(pipes);
        lenient().when(services.dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(pipes.kafkaRestBridge()).thenReturn(bridge);
        lenient().when(bridge.defaultImage()).thenReturn("ghcr.io/aiven-open/karapace:latest");
        lenient().when(bridge.hostPortBase()).thenReturn(9500);
        lenient().when(bridge.hostPortMax()).thenReturn(9599);
        lenient().when(config.docker()).thenReturn(docker);
        lenient().when(docker.logMaxSize()).thenReturn("10m");
        lenient().when(docker.logMaxFile()).thenReturn("3");

        lenient().when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        manager = new KarapaceManager(containerBuilder, lifecycleManager, containerDetector, config, portAllocator,
                dockerHostResolver, java.time.Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        if (fakeRestServer != null) {
            fakeRestServer.stop(0);
        }
    }

    /** Stands in for Karapace's own {@code GET /_health}, which {@code isReady} polls. */
    private int startFakeRestServer() throws Exception {
        fakeRestServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeRestServer.createContext("/_health", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeRestServer.start();
        return fakeRestServer.getAddress().getPort();
    }

    @Test
    void ensureStartedConfiguresBootstrapUriAndPublishesRestPort() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);

        ContainerInfo info = new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        URI restBaseUri = manager.ensureStarted("broker-1:9092");

        assertEquals("http://localhost:" + hostPort + "/", restBaseUri.toString());

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(specCaptor.capture());
        ContainerSpec spec = specCaptor.getValue();
        assertTrue(spec.env().contains("KARAPACE_BOOTSTRAP_URI=broker-1:9092"),
                "Karapace must be pointed at the target cluster's bootstrap servers");
        assertEquals(Integer.valueOf(hostPort), spec.portBindings().get(REST_PORT));
    }

    @Test
    void ensureStartedReusesTheSameContainerForTheSameTarget() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);

        ContainerInfo info = new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        URI first = manager.ensureStarted("broker-1:9092");
        URI second = manager.ensureStarted("broker-1:9092");

        assertSame(first, second, "multiple pipes reading the same broker must share one Karapace instance");
        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void ensureStartedRewritesHostLocalBrokerForTheSidecar() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        ContainerInfo info = new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.ensureStarted("localhost:9092");

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(specCaptor.capture());
        assertTrue(specCaptor.getValue().env().contains("KARAPACE_BOOTSTRAP_URI=host.docker.internal:9092"),
                "a host-local broker address must be rewritten so the sidecar, in its own network "
                        + "namespace, reaches the Floci host instead of itself");
    }

    @Test
    void ensureStartedAddsTheLinuxHostGatewayAliasSoRewrittenAddressesResolve() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);

        ContainerInfo info = new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.ensureStarted("localhost:9092");

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(specCaptor.capture());
        assertTrue(specCaptor.getValue().extraHosts().contains("host.docker.internal:host-gateway"),
                "native Linux Docker does not auto-inject host.docker.internal, so the sidecar needs "
                        + "the host-gateway mapping to resolve the address resolveForContainer rewrote to it");
    }

    @Test
    void ensureStartedCleansUpWhenReadinessNeverArrives() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(54321);

        ContainerInfo info = new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", 1)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> manager.ensureStarted("broker-1:9092"));

        verify(lifecycleManager).stopAndRemove("container-1", null);
        verify(portAllocator).release(54321);
    }
}
