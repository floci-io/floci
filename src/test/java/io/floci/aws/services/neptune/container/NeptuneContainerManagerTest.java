package io.floci.aws.services.neptune.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.testing.ParallelLocks;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class NeptuneContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(NeptuneContainerManagerTest.class);

    @Test
    void stopByClusterIdRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        NeptuneContainerManager manager = new NeptuneContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class));

        // No container was ever registered for this id (e.g. it failed before registration).
        // Rollback must still fall back to the deterministic name so nothing is orphaned.
        manager.stopByClusterId("my-cluster");

        verify(lifecycleManager).removeIfExists("floci-aws-neptune-my-cluster");
    }

    @Test
    void tryStartReportsUnavailableInsteadOfThrowingWhenNoDockerDaemonIsReachable() {
        // Floci running inside Docker without a mounted daemon socket: the Neptune control
        // plane must keep working, so the failure is reported rather than propagated.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(lifecycleManager.isDockerReachable()).thenReturn(false);

        NeptuneContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN,
                    "000000000000", "us-east-1", null),
                    "attempt " + attempt + " should report unavailable");
        }
        assertFalse(manager.isDockerReachable());
    }

    @Test
    void tryStartPropagatesFailuresRaisedWhileTheDaemonIsReachable() {
        // A reachable daemon that cannot start the container is a genuine failure, not a
        // degraded mode: CreateDBCluster must still surface it.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("no such image: tinkerpop/gremlin-server:3.7.3"));
        when(lifecycleManager.isDockerReachable()).thenReturn(true);

        NeptuneContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN,
                        "000000000000", "us-east-1", null));
        assertEquals("no such image: tinkerpop/gremlin-server:3.7.3", failure.getMessage());
    }

    @Test
    void tryStartReturnsTheHandleOnceADaemonIsReachable() throws IOException, InterruptedException {
        // Real loopback socket standing in for the Gremlin backend's port: any reply to the
        // manager's readiness probe confirms it is listening.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // Test teardown races the socket close; nothing left to assert on.
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(8182, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            NeptuneContainerManager manager = newManager(lifecycleManager);

            NeptuneContainerHandle handle =
                    manager.tryStart("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN,
                            "000000000000", "us-east-1", null);

            assertEquals("container-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
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
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(8182, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(new ContainerSpec("test-image"));

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.NeptuneServiceConfig neptune = mock(EmulatorConfig.NeptuneServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.neptune()).thenReturn(neptune);
            when(neptune.dockerNetwork()).thenReturn(Optional.empty());

            NeptuneContainerManager manager = new NeptuneContainerManager(containerBuilder, lifecycleManager,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN,
                    "111111111111", "eu-west-1", null);

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "neptune",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "111111111111",
                    "io.floci.region", "eu-west-1"));
            verify(builder).withName("floci-aws-neptune-111111111111-eu-west-1-cluster1");
        }
    }

    @Test
    void aNeo4jClusterWithAVolumeKeepsItsDataDirectoryThere() throws IOException {
        try (ServerSocket backend = backend(BOLT_REPLY)) {
            ContainerLifecycleManager lifecycle = startsOn(backend, 7687);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            NeptuneContainerManager manager = newManager(lifecycle, builder, storage("persistent", null, false));

            manager.start("cluster1", "neo4j:5-community", NeptuneDbType.NEO4J, "000000000000", "us-east-1",
                    "vol-1");

            verify(lifecycle).ensureVolume("floci-aws-neptune-vol-1");
            verify(builder).withNamedVolume("floci-aws-neptune-vol-1", "/data");
            verify(builder, never()).withUser(anyString());
            verify(lifecycle).createAndStart(any(ContainerSpec.class));
        }
    }

    @Test
    void aGremlinClusterWithAVolumeSavesItsGraphThere() throws IOException {
        try (ServerSocket backend = backend(GREMLIN_REPLY)) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            ArgumentCaptor<Consumer<String>> beforeStart = ArgumentCaptor.captor();
            when(lifecycle.createAndStart(any(ContainerSpec.class), beforeStart.capture()))
                    .thenReturn(info(backend, 8182));
            DockerClient docker = mock(DockerClient.class);
            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycle.getDockerClient()).thenReturn(docker);
            when(docker.copyArchiveToContainerCmd("container-id")).thenReturn(copy);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            NeptuneContainerManager manager = newManager(lifecycle, builder, storage("persistent", null, false));

            manager.start("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN, "000000000000",
                    "us-east-1", "vol-1");
            beforeStart.getValue().accept("container-id");

            verify(builder).withNamedVolume("floci-aws-neptune-vol-1", "/opt/gremlin-server/data");
            verify(builder, never()).withUser(anyString());
            verify(copy).withRemotePath("/opt/gremlin-server/conf");
            ArgumentCaptor<InputStream> tar = ArgumentCaptor.forClass(InputStream.class);
            verify(copy).withTarInputStream(tar.capture());
            String archive = new String(tar.getValue().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(archive.contains("tinkergraph-empty.properties"), archive);
            assertTrue(archive.contains(
                    "gremlin.tinkergraph.graphLocation=/opt/gremlin-server/data/floci-graph.kryo\n"), archive);
            assertTrue(archive.contains("gremlin.tinkergraph.graphFormat=gryo\n"), archive);
            assertTrue(archive.contains("gremlin.tinkergraph.vertexIdManager=LONG\n"), archive);
            verify(copy).exec();
        }
    }

    @Test
    void aGremlinImageLaidOutOtherwiseStillStartsWithItsGraphInMemory() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ArgumentCaptor<Consumer<String>> beforeStart = ArgumentCaptor.captor();
        DockerClient docker = mock(DockerClient.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.copyArchiveToContainerCmd("container-id"))
                .thenThrow(new NotFoundException("Could not find the file /opt/gremlin-server/conf in container"));
        when(lifecycle.createAndStart(any(ContainerSpec.class), beforeStart.capture()))
                .thenThrow(new IllegalStateException("stop after capturing the setup"));
        NeptuneContainerManager manager = newManager(lifecycle, mock(ContainerBuilder.Builder.class,
                Mockito.RETURNS_SELF), storage("persistent", null, false));
        assertThrows(IllegalStateException.class, () -> manager.start("cluster1", "custom/gremlin:1",
                NeptuneDbType.GREMLIN, "000000000000", "us-east-1", "vol-1"));

        beforeStart.getValue().accept("container-id");

        verify(docker, Mockito.atLeastOnce()).copyArchiveToContainerCmd("container-id");
    }

    @Test
    void aGremlinClusterUnderTheHostPathRunsAsRootToWriteItsGraph(@TempDir Path hostPath) throws IOException {
        try (ServerSocket backend = backend(GREMLIN_REPLY)) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.createAndStart(any(ContainerSpec.class), ArgumentMatchers.<Consumer<String>>any()))
                    .thenReturn(info(backend, 8182));
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            NeptuneContainerManager manager = newManager(lifecycle, builder,
                    storage("persistent", hostPath.toString(), false));

            manager.start("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN, "000000000000",
                    "us-east-1", "vol-1");

            Path dataDir = hostPath.resolve("neptune").resolve("vol-1");
            verify(builder).withBind(dataDir.toString(), "/opt/gremlin-server/data");
            assertTrue(Files.isDirectory(dataDir));
            verify(builder).withUser("0");
            verify(builder, never()).withNamedVolume(anyString(), anyString());
        }
    }

    @Test
    void aNeo4jClusterUnderTheHostPathKeepsTheImagesUser(@TempDir Path hostPath) throws IOException {
        try (ServerSocket backend = backend(BOLT_REPLY)) {
            ContainerLifecycleManager lifecycle = startsOn(backend, 7687);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            NeptuneContainerManager manager = newManager(lifecycle, builder,
                    storage("persistent", hostPath.toString(), false));

            manager.start("cluster1", "neo4j:5-community", NeptuneDbType.NEO4J, "000000000000", "us-east-1",
                    "vol-1");

            verify(builder).withBind(hostPath.resolve("neptune").resolve("vol-1").toString(), "/data");
            verify(builder, never()).withUser(anyString());
        }
    }

    @Test
    void aClusterWithoutAVolumeMountsNothingAndKeepsTheImagesGraph() throws IOException {
        try (ServerSocket backend = backend(GREMLIN_REPLY)) {
            ContainerLifecycleManager lifecycle = startsOn(backend, 8182);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            NeptuneContainerManager manager = newManager(lifecycle, builder, storage("memory", null, false));

            manager.start("cluster1", "tinkerpop/gremlin-server:3.7.3", NeptuneDbType.GREMLIN, "000000000000",
                    "us-east-1", null);

            verify(builder, never()).withNamedVolume(anyString(), anyString());
            verify(builder, never()).withBind(anyString(), anyString());
            verify(lifecycle).createAndStart(any(ContainerSpec.class));
            verify(lifecycle, never()).createAndStart(any(ContainerSpec.class),
                    ArgumentMatchers.<Consumer<String>>any());
        }
    }

    @Test
    void aContainerHasTimeToSaveItsGraphWhenItStops() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        NeptuneContainerHandle handle = new NeptuneContainerHandle("container-id", "cluster1", "127.0.0.1", 8182);

        newManager(lifecycle).stop(handle);

        verify(lifecycle).stopAndRemove("container-id", null, 30);
    }

    @Test
    void removingStorageFollowsThePolicyAndLeavesHostDirectoriesAlone(@TempDir Path hostPath) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", null, false))
                .removeStorage("kept");
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", hostPath.toString(), true))
                .removeStorage("host");
        NeptuneContainerManager pruning = newManager(lifecycle, mock(ContainerBuilder.Builder.class),
                storage("persistent", null, true));
        pruning.removeStorage(null);
        pruning.removeStorage("pruned");

        verify(lifecycle).removeVolume("floci-aws-neptune-pruned");
        verify(lifecycle, times(1)).removeVolume(anyString());
    }

    @Test
    void removingStorageFollowsNeptunesOwnStorageMode() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("memory", "persistent", null, false))
                .removeStorage("kept-by-neptune");
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", "memory", null, false))
                .removeStorage("neptune-in-memory");
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("memory", null, null, false))
                .removeStorage("all-in-memory");

        verify(lifecycle, never()).removeVolume("floci-aws-neptune-kept-by-neptune");
        verify(lifecycle).removeVolume("floci-aws-neptune-neptune-in-memory");
        verify(lifecycle).removeVolume("floci-aws-neptune-all-in-memory");
    }

    @Test
    void discardingStorageDropsTheVolumeEvenWhenThePolicyRetainsVolumes(@TempDir Path hostPath) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        NeptuneContainerManager manager = newManager(lifecycle, mock(ContainerBuilder.Builder.class),
                storage("persistent", null, false));

        manager.discardStorage("vol-1");
        manager.discardStorage(null);
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", hostPath.toString(), false))
                .discardStorage("vol-2");

        verify(lifecycle).removeVolume("floci-aws-neptune-vol-1");
        verify(lifecycle, times(1)).removeVolume(anyString());
    }

    private static final byte[] GREMLIN_REPLY = "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BOLT_REPLY = {0x00, 0x00, 0x00, 0x05};

    /** A loopback backend that answers one readiness probe with {@code reply}. */
    private static ServerSocket backend(byte[] reply) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        Thread responder = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                socket.getInputStream().read(new byte[64]);
                OutputStream out = socket.getOutputStream();
                out.write(reply);
                out.flush();
            } catch (IOException e) {
                LOG.debugv(e, "Acceptor socket closed during test teardown");
            }
        });
        responder.setDaemon(true);
        responder.start();
        return serverSocket;
    }

    private static ContainerLifecycleManager.ContainerInfo info(ServerSocket backend, int backendPort) {
        return new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(backendPort,
                new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort())));
    }

    private static ContainerLifecycleManager startsOn(ServerSocket backend, int backendPort) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any(ContainerSpec.class))).thenReturn(info(backend, backendPort));
        return lifecycle;
    }

    private static EmulatorConfig.StorageConfig storage(String mode, String hostPersistentPath, boolean prune) {
        return storage(mode, null, hostPersistentPath, prune);
    }

    /** Storage settings with {@code neptuneMode} as Neptune's own storage mode, none when null. */
    private static EmulatorConfig.StorageConfig storage(String mode, String neptuneMode, String hostPersistentPath,
                                                        boolean prune) {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.ServiceStorageOverrides overrides = mock(EmulatorConfig.ServiceStorageOverrides.class);
        EmulatorConfig.NeptuneStorageConfig neptune = mock(EmulatorConfig.NeptuneStorageConfig.class);
        when(storage.mode()).thenReturn(mode);
        when(storage.hostPersistentPath()).thenReturn(hostPersistentPath);
        when(storage.pruneVolumesOnDelete()).thenReturn(prune);
        lenient().when(storage.services()).thenReturn(overrides);
        lenient().when(overrides.neptune()).thenReturn(neptune);
        lenient().when(neptune.mode()).thenReturn(Optional.ofNullable(neptuneMode));
        return storage;
    }

    private NeptuneContainerManager newManager(ContainerLifecycleManager lifecycleManager,
                                               ContainerBuilder.Builder builder,
                                               EmulatorConfig.StorageConfig storage) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.NeptuneServiceConfig neptune = mock(EmulatorConfig.NeptuneServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.neptune()).thenReturn(neptune);
        when(neptune.dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage()).thenReturn(storage);
        return new NeptuneContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));
    }

    private NeptuneContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.NeptuneServiceConfig neptune = mock(EmulatorConfig.NeptuneServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.neptune()).thenReturn(neptune);
        when(neptune.dockerNetwork()).thenReturn(Optional.empty());

        return new NeptuneContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));
    }
}
