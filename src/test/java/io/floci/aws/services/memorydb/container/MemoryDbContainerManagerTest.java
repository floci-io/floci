package io.floci.aws.services.memorydb.container;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbContainerManagerTest {

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
            CompletableFuture<Void> responder = startPongResponder(backend, 1);
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
                responder.get(5, TimeUnit.SECONDS);
            }
            verify(lifecycle).stopAndRemoveStrict("late-cid", null, 5, true);
        }
    }

    @Test
    void stopByClusterNameRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        MemoryDbContainerManager manager = newManager(lifecycleManager);

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
    void tryStartReturnsTheHandleOnceADaemonIsReachable() throws Exception {
        // Real loopback socket standing in for the backend container's Redis port, so the
        // manager's PING/PONG readiness probe succeeds without a real container.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> responder = startPongResponder(serverSocket, 1);
            ContainerLifecycleManager lifecycleManager = lifecycleAnswering(serverSocket);
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
            responder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void startLabelsContainerWithResourceIdentity() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> responder = startPongResponder(serverSocket, 1);
            ContainerBuilder.Builder builder = builder();
            MemoryDbContainerManager manager = newManager(containers(builder), lifecycleAnswering(serverSocket),
                    mock(ContainerLogStreamer.class));

            manager.start("cluster1", "valkey/valkey:8");

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "memorydb",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "us-east-1"));
            responder.get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * In host-path mode the bind source names a directory on the Docker host: Floci creates it only
     * when it runs there, and inside a container leaves it to the daemon, as the other data planes do.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostPathModeCreatesTheBindSourceOnlyOnTheDockerHost(boolean inContainer, @TempDir Path directory)
            throws Exception {
        Path bindSource = directory.resolve("floci-data").resolve("memorydb").resolve("vol-1");
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> responder = startPongResponder(serverSocket, 1);
            ContainerBuilder.Builder builder = builder();
            ContainerDetector containerDetector = mock(ContainerDetector.class);
            when(containerDetector.isRunningInContainer()).thenReturn(inContainer);
            MemoryDbContainerManager manager = newManager(containers(builder), lifecycleAnswering(serverSocket),
                    mock(ContainerLogStreamer.class), containerDetector, directory.resolve("floci-data").toString());

            manager.start("cluster1", "valkey/valkey:8", "vol-1", "000000000000", "us-east-1");

            verify(builder).withBind(bindSource.toString(), "/data");
            assertEquals(!inContainer, Files.isDirectory(bindSource));
            responder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void logicalDefaultNamesKeepLogsAndContainersWhileRuntimeKeysStayScoped() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerBuilder.Builder builder = builder();
        ContainerLogStreamer logs = mock(ContainerLogStreamer.class);
        when(logs.generateLogStreamName(anyString())).thenReturn("engine");
        MemoryDbContainerManager manager = newManager(containers(builder), lifecycle, logs);
        String defaultIdentity = "000000000000-us-east-1-cache";
        String collisionIdentity = "000000000000-us-east-1-111111111111-eu-west-1-same";
        String foreignIdentity = "111111111111-eu-west-1-same";
        try (ServerSocket backend = new ServerSocket(0)) {
            CompletableFuture<Void> responder = startPongResponder(backend, 3);
            when(lifecycle.createAndStartEphemeral(any())).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("default-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))),
                    new ContainerLifecycleManager.ContainerInfo("collision-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))),
                    new ContainerLifecycleManager.ContainerInfo("foreign-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))));
            try {
                MemoryDbContainerHandle first = manager.tryStart(defaultIdentity, "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "cache");
                MemoryDbContainerHandle collision = manager.tryStart(collisionIdentity, "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "111111111111-eu-west-1-same");
                MemoryDbContainerHandle foreign = manager.tryStart(foreignIdentity, "valkey/valkey:8", null,
                        "111111111111", "eu-west-1", "same");
                assertEquals(defaultIdentity, first.getClusterName());
                assertEquals(collisionIdentity, collision.getClusterName());
                assertEquals(foreignIdentity, foreign.getClusterName());
                ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
                verify(builder, times(3)).withName(names.capture());
                assertEquals(List.of("floci-aws-memorydb-cache",
                        "floci-aws-memorydb-111111111111-eu-west-1-same",
                        "floci-aws-memorydb-111111111111_eu-west-1_same"), names.getAllValues());
                verify(logs).attach("default-cid", "/aws/memorydb/cluster/cache/engine-log", "engine",
                        "us-east-1", "memorydb:" + defaultIdentity);
                verify(logs).attach("collision-cid",
                        "/aws/memorydb/cluster/111111111111-eu-west-1-same/engine-log", "engine",
                        "us-east-1", "memorydb:" + collisionIdentity);
                verify(logs).attach("foreign-cid",
                        "/aws/memorydb/cluster/111111111111-eu-west-1-same/engine-log", "engine",
                        "eu-west-1", "memorydb:" + foreignIdentity);
                responder.get(5, TimeUnit.SECONDS);
            } finally {
                backend.close();
                manager.stopAll();
            }
        }
        verify(lifecycle).stopAndRemoveStrict("default-cid", null, 5, true);
        verify(lifecycle).stopAndRemoveStrict("collision-cid", null, 5, true);
        verify(lifecycle).stopAndRemoveStrict("foreign-cid", null, 5, true);
    }

    @Test
    void failedCreationRollbackUsesTheSameDefaultAndForeignContainerNames() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        RuntimeException failure = new IllegalStateException("creation failed before registration");
        when(lifecycle.createAndStartEphemeral(any())).thenThrow(failure);
        MemoryDbContainerManager manager = newManager(containers(builder()), lifecycle,
                mock(ContainerLogStreamer.class));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> manager.start("000000000000-us-east-1-cache", "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "cache")));
        manager.stopByClusterName("000000000000-us-east-1-cache", "000000000000", "us-east-1", "cache");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> manager.start("111111111111-eu-west-1-cache", "valkey/valkey:8", null,
                        "111111111111", "eu-west-1", "cache")));
        manager.stopByClusterName("111111111111-eu-west-1-cache", "111111111111", "eu-west-1", "cache");
        verify(lifecycle, times(2)).removeIfExistsStrict("floci-aws-memorydb-cache", true);
        verify(lifecycle, times(2)).removeIfExistsStrict("floci-aws-memorydb-111111111111_eu-west-1_cache", true);
    }

    private static CompletableFuture<Void> startPongResponder(ServerSocket backend, int connections) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Thread responder = new Thread(() -> {
            try {
                for (int i = 0; i < connections; i++) {
                    try (Socket socket = backend.accept()) {
                        socket.setSoTimeout(5000);
                        socket.getInputStream().read(new byte[64]);
                        socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                }
                completed.complete(null);
            } catch (IOException e) {
                completed.completeExceptionally(e);
            }
        });
        responder.setDaemon(true);
        responder.start();
        return completed;
    }

    /** A lifecycle whose one backend, {@code container-id}, answers on {@code backend}'s port. */
    private static ContainerLifecycleManager lifecycleAnswering(ServerSocket backend) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStartEphemeral(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                        "127.0.0.1", backend.getLocalPort()))));
        return lifecycle;
    }

    /** A container builder that returns itself from every call and builds a spec of {@code test-image}. */
    private static ContainerBuilder.Builder builder() {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        return builder;
    }

    private static ContainerBuilder containers(ContainerBuilder.Builder builder) {
        ContainerBuilder containers = mock(ContainerBuilder.class);
        when(containers.newContainer(anyString())).thenReturn(builder);
        return containers;
    }

    private MemoryDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        return newManager(containers(builder()), lifecycleManager, logStreamer);
    }

    private MemoryDbContainerManager newManager(ContainerBuilder containerBuilder,
                                                ContainerLifecycleManager lifecycleManager,
                                                ContainerLogStreamer logStreamer) {
        return newManager(containerBuilder, lifecycleManager, logStreamer, mock(ContainerDetector.class), null);
    }

    /** As above, where Floci runs as {@code containerDetector} says, with {@code hostPersistentPath} as its storage's. */
    private MemoryDbContainerManager newManager(ContainerBuilder containerBuilder,
                                                ContainerLifecycleManager lifecycleManager,
                                                ContainerLogStreamer logStreamer,
                                                ContainerDetector containerDetector, String hostPersistentPath) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(services);
        when(services.memorydb()).thenReturn(memorydb);
        when(memorydb.dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn(hostPersistentPath);

        return new MemoryDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, new RegionResolver("us-east-1", "000000000000"));
    }
}
