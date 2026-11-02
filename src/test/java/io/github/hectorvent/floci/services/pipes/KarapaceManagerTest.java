package io.github.hectorvent.floci.services.pipes;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                dockerHostResolver, Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        if (fakeRestServer != null) {
            fakeRestServer.stop(0);
        }
    }

    /** Stands in for Karapace's own {@code GET /_health}, which {@code isReady} polls. */
    private int startFakeRestServer() throws Exception {
        return startFakeRestServer(new AtomicInteger(200));
    }

    private int startFakeRestServer(AtomicInteger healthStatus) throws Exception {
        fakeRestServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeRestServer.createContext("/_health", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(healthStatus.get(), body.length);
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
    void bridgeContainerCarriesThePipesIdentityAndItsComponent() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("container-1",
                Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));

        manager.ensureStarted("broker-1:9092");

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(specCaptor.capture());
        // The legacy floci.component alias is added where every container is created, not here.
        assertEquals(
                Map.of("io.floci", "aws", "io.floci.service", "pipes",
                        "io.floci.component", "pipes-kafka-rest-bridge"),
                specCaptor.getValue().labels());
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

        assertThrows(IllegalStateException.class,
                () -> manager.ensureStarted("broker-1:9092"));

        verify(lifecycleManager).stopAndRemoveStrict("container-1", null);
        verify(portAllocator).release(54321);
    }

    @Test
    void managedTeardownStopsEveryTargetAndPredestroyDoesNotStopThemAgain() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));
        manager.ensureStarted("broker-1:9092");
        manager.ensureStarted("broker-2:9092");

        ContainerTeardown teardown = assertInstanceOf(ContainerTeardown.class, manager);
        teardown.stopManagedContainers();
        manager.shutdown();

        verify(lifecycleManager, times(1)).stopAndRemoveStrict("container-1", null);
        verify(lifecycleManager, times(1)).stopAndRemoveStrict("container-2", null);
    }

    @Test
    void managedTeardownClearsCachedTargetBeforeTheNextStart() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));
        manager.ensureStarted("broker-1:9092");

        ContainerTeardown teardown = assertInstanceOf(ContainerTeardown.class, manager);
        teardown.stopManagedContainers();
        manager.ensureStarted("broker-1:9092");
        teardown.stopManagedContainers();

        verify(lifecycleManager, times(2)).createAndStart(any());
        verify(lifecycleManager, times(1)).stopAndRemoveStrict("container-1", null);
        verify(lifecycleManager, times(1)).stopAndRemoveStrict("container-2", null);
    }

    @Test
    void failedManagedStopDoesNotPreventStoppingAnotherTarget() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));
        manager.ensureStarted("broker-1:9092");
        manager.ensureStarted("broker-2:9092");
        doThrow(new IllegalStateException("Docker stop failed"))
                .when(lifecycleManager).stopAndRemoveStrict("container-1", null);

        ContainerTeardown teardown = assertInstanceOf(ContainerTeardown.class, manager);
        teardown.stopManagedContainers();

        verify(lifecycleManager).stopAndRemoveStrict("container-1", null);
        verify(lifecycleManager).stopAndRemoveStrict("container-2", null);
    }
    @Test
    void hostModeResetReusesTheOnlyAvailablePort() throws Exception {
        int endpointPort = startFakeRestServer();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().pipes().kafkaRestBridge().hostPortBase()).thenReturn(port);
        when(config.services().pipes().kafkaRestBridge().hostPortMax()).thenReturn(port);
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        PortAllocator allocator = new PortAllocator();
        manager = new KarapaceManager(new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer),
                lifecycleManager, containerDetector, config, allocator, dockerHostResolver, Duration.ofMillis(500));
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", endpointPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", endpointPort))));

        manager.ensureStarted("broker:9092");
        manager.stopManagedContainers();
        assertDoesNotThrow(() -> manager.ensureStarted("broker:9092"));
        manager.stopManagedContainers();

        ArgumentCaptor<ContainerSpec> specs = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, times(2)).createAndStart(specs.capture());
        for (ContainerSpec spec : specs.getAllValues()) {
            assertEquals(port, spec.portBindings().get(REST_PORT));
        }
        assertEquals(port, allocator.allocate(port, port));
        allocator.release(port);
    }

    @Test
    void resetDrainsAnAdmittedStartAndRejectsNewStartsUntilCleanupEnds() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finishCreating = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        lenient().doAnswer(call -> {
            cleanupEntered.countDown();
            assertTrue(finishCleanup.await(5, TimeUnit.SECONDS));
            return null;
        }).when(lifecycleManager).stopAndRemoveStrict("container-1", null);
        when(lifecycleManager.createAndStart(any())).thenAnswer(call -> {
            ContainerSpec spec = call.getArgument(0);
            if (spec.env().contains("KARAPACE_BOOTSTRAP_URI=other:9092")) {
                return new ContainerInfo("other-container",
                        Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
            }
            creating.countDown();
            assertTrue(finishCreating.await(5, TimeUnit.SECONDS));
            return new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort)));
        });
        FutureTask<URI> start = new FutureTask<>(() -> manager.ensureStarted("broker:9092"));
        FutureTask<Void> reset = new FutureTask<>(() -> {
            manager.stopManagedContainers();
            return null;
        });
        Thread starting = new Thread(start, "karapace-test-start");
        Thread resetting = new Thread(reset, "karapace-test-reset");
        starting.start();
        try {
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            resetting.start();
            awaitBlockedOrDone(resetting);
            assertFalse(reset.isDone(), "reset must wait for the admitted create before taking ownership");
            assertThrows(IllegalStateException.class, () -> manager.ensureStarted("other:9092"));
            verify(lifecycleManager, never()).stopAndRemoveStrict("container-1", null);
            finishCreating.countDown();
            start.get(5, TimeUnit.SECONDS);
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(reset.isDone(), "reset must finish strict removal before reopening admission");
            assertThrows(IllegalStateException.class, () -> manager.ensureStarted("other:9092"));
            verify(portAllocator, never()).release(hostPort);
            finishCleanup.countDown();
            reset.get(5, TimeUnit.SECONDS);
            verify(lifecycleManager).stopAndRemoveStrict("container-1", null);
            verify(portAllocator).release(hostPort);
            manager.ensureStarted("broker:9092");
            verify(lifecycleManager, times(2)).createAndStart(any());
        } finally {
            finishCreating.countDown();
            finishCleanup.countDown();
            starting.join(5000);
            resetting.join(5000);
            assertFalse(starting.isAlive());
            assertFalse(resetting.isAlive());
        }
    }

    @Test
    void failedStopRetainsContainerAndPortForRetry() throws Exception {
        int hostPort = startFakeRestServer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));
        manager.ensureStarted("broker:9092");
        lenient().doThrow(new IllegalStateException("Docker stop failed")).doNothing()
                .when(lifecycleManager).stopAndRemoveStrict("container-1", null);

        manager.stopManagedContainers();

        verify(portAllocator, never()).release(hostPort);
        assertDoesNotThrow(() -> manager.ensureStarted("broker:9092"));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict("container-1", null);
        verify(portAllocator).release(hostPort);
        manager.stopManagedContainers();
        verify(lifecycleManager).stopAndRemoveStrict("container-2", null);
        verify(portAllocator, times(2)).release(hostPort);
    }

    @Test
    void failedReadinessCleanupRetriesBeforeAllocatingAReplacement() throws Exception {
        AtomicInteger healthStatus = new AtomicInteger(503);
        int hostPort = startFakeRestServer(healthStatus);
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(hostPort);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerInfo("container-1", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))),
                new ContainerInfo("container-2", Map.of(REST_PORT, new EndpointInfo("localhost", hostPort))));
        IllegalStateException cleanupFailure = new IllegalStateException("Docker removal unavailable");
        lenient().doThrow(cleanupFailure).doThrow(cleanupFailure).doNothing()
                .when(lifecycleManager).stopAndRemoveStrict("container-1", null);

        IllegalStateException readinessFailure = assertThrows(IllegalStateException.class,
                () -> manager.ensureStarted("broker:9092"));
        assertTrue(readinessFailure.getMessage().contains("did not become ready"));
        assertEquals(1, readinessFailure.getSuppressed().length);
        assertSame(cleanupFailure, readinessFailure.getSuppressed()[0]);
        verify(portAllocator, never()).release(hostPort);

        assertSame(cleanupFailure, assertThrows(IllegalStateException.class,
                () -> manager.ensureStarted("broker:9092")));
        verify(portAllocator, never()).release(hostPort);
        verify(portAllocator).allocate(9500, 9599);
        verify(lifecycleManager).createAndStart(any());

        healthStatus.set(200);
        assertEquals(URI.create("http://localhost:" + hostPort + "/"), manager.ensureStarted("broker:9092"));
        InOrder cleanupOrder = inOrder(lifecycleManager, portAllocator);
        cleanupOrder.verify(lifecycleManager, times(3)).stopAndRemoveStrict("container-1", null);
        cleanupOrder.verify(portAllocator).release(hostPort);
        cleanupOrder.verify(portAllocator).allocate(9500, 9599);
        cleanupOrder.verify(lifecycleManager).createAndStart(any());
        verify(lifecycleManager, times(3)).stopAndRemoveStrict("container-1", null);
        verify(portAllocator).release(hostPort);
        verify(portAllocator, times(2)).allocate(9500, 9599);
        verify(lifecycleManager, times(2)).createAndStart(any());
        manager.stopManagedContainers();
        verify(lifecycleManager).stopAndRemoveStrict("container-2", null);
        verify(portAllocator, times(2)).release(hostPort);
    }

    @Test
    void createFailureReleasesItsAllocatedPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9500, 9599)).thenReturn(9500);
        IllegalStateException failure = new IllegalStateException("create failed");
        when(lifecycleManager.createAndStart(any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.ensureStarted("broker:9092")));

        verify(portAllocator).release(9500);
    }

}
