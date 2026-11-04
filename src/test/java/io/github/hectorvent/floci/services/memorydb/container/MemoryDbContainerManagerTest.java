package io.github.hectorvent.floci.services.memorydb.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(MemoryDbContainerManagerTest.class);

    @Test
    void failedStartPreservesTheCleanupAwareLifecycleFailure() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        RuntimeException failure = new RuntimeException("backend start denied");
        when(lifecycle.createAndStartEphemeral(any())).thenThrow(failure);
        MemoryDbContainerManager manager = newManager(lifecycle);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> manager.start("cache", "valkey/valkey:8")));
    }

    @Test
    void stoppingBackendReclaimsImageDeclaredAnonymousVolumes() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        MemoryDbContainerManager manager = newManager(lifecycle);
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("owned-cid", "cache", "localhost", 6379);

        manager.stop(handle);

        verify(lifecycle).stopAndRemoveStrict("owned-cid", null, 5, true);
    }

    @Test
    void shutdownRejectsNewBackendStarts() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        MemoryDbContainerManager manager = newManager(lifecycle);

        manager.stopAll();

        assertThrows(IllegalStateException.class, () -> manager.start("late", "valkey/valkey:8"));
        verify(lifecycle, Mockito.never()).createAndStartEphemeral(any());
    }

    @Test
    void shutdownWaitsForAdmittedCreateAndRemovesItsLateBackend() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        try (ServerSocket backend = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = backend.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Backend probe socket closed during teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.createAndStartEphemeral(any())).thenAnswer(inv -> {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return new ContainerLifecycleManager.ContainerInfo("late-cid",
                        Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                                "127.0.0.1", backend.getLocalPort())));
            });
            MemoryDbContainerManager manager = newManager(lifecycle);
            CompletableFuture<MemoryDbContainerHandle> starting =
                    CompletableFuture.supplyAsync(() -> manager.start("late", "valkey/valkey:8"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
                shutdownStarted.countDown();
                manager.stopAll();
            });
            try {
                assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> shutdown.get(1, TimeUnit.SECONDS));
            } finally {
                finish.countDown();
                assertThrows(ExecutionException.class, () -> starting.get(5, TimeUnit.SECONDS));
                shutdown.get(5, TimeUnit.SECONDS);
                responder.join(5000);
            }
            verify(lifecycle).stopAndRemoveStrict("late-cid", null, 5, true);
        }
    }

    @Test
    void stopByClusterNameRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        MemoryDbContainerManager manager = new MemoryDbContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class));

        // No container was ever registered for this name (e.g. it failed before registration).
        // Rollback must still fall back to the deterministic name so nothing is orphaned.
        manager.stopByClusterName("my-cluster");

        verify(lifecycleManager).removeIfExistsStrict("floci-aws-memorydb-my-cluster", true);
    }

    @Test
    void tryStartReportsUnavailableInsteadOfThrowingWhenNoDockerDaemonIsReachable() {
        // Floci running inside Docker without a mounted daemon socket: the MemoryDB control
        // plane must keep working, so the failure is reported rather than propagated.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStartEphemeral(any()))
                .thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(lifecycleManager.getDockerClient()).thenThrow(
                new RuntimeException("java.net.SocketException: No such file or directory"));

        MemoryDbContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "valkey/valkey:8"),
                    "attempt " + attempt + " should report unavailable");
        }
        assertFalse(manager.isDockerReachable());
    }

    @Test
    void tryStartPropagatesFailuresRaisedWhileTheDaemonIsReachable() {
        // A reachable daemon that cannot start the container is a genuine failure, not a
        // degraded mode: CreateCluster must still surface it.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStartEphemeral(any()))
                .thenThrow(new RuntimeException("no such image: valkey/valkey:8"));
        DockerClient dockerClient = mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        MemoryDbContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "valkey/valkey:8"));
        assertEquals("no such image: valkey/valkey:8", failure.getMessage());
    }

    @Test
    void tryStartReturnsTheHandleOnceADaemonIsReachable() throws IOException, InterruptedException {
        // Real loopback socket standing in for the backend container's Redis port, so the
        // manager's PING/PONG readiness probe succeeds without a real container.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // Test teardown races the socket close; nothing left to assert on.
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStartEphemeral(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            MemoryDbContainerManager manager = newManager(lifecycleManager);

            MemoryDbContainerHandle handle = manager.tryStart("cluster1", "valkey/valkey:8");

            assertEquals("container-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
            Mockito.doThrow(new RuntimeException("cleanup rejected")).doNothing()
                    .when(lifecycleManager).stopAndRemoveStrict("container-id", null, 5, true);
            assertThrows(RuntimeException.class, () -> manager.stop(handle));
            manager.stopAll();
            manager.stopAll();
            verify(lifecycleManager, Mockito.times(2)).stopAndRemoveStrict("container-id", null, 5, true);
            responder.join(5000);
        }
    }

    @Test
    void startLabelsContainerWithResourceIdentity() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStartEphemeral(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(new ContainerSpec("test-image"));

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.memorydb()).thenReturn(memorydb);
            when(memorydb.dockerNetwork()).thenReturn(Optional.empty());

            MemoryDbContainerManager manager = new MemoryDbContainerManager(containerBuilder, lifecycleManager,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "valkey/valkey:8");

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "memorydb",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "us-east-1"));
        }
    }

    private MemoryDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.memorydb()).thenReturn(memorydb);
        when(memorydb.dockerNetwork()).thenReturn(Optional.empty());

        return new MemoryDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));
    }
}
