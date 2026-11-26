package io.floci.aws.services.docdb.container;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.testing.ParallelLocks;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Mockito;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class DocDbContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(DocDbContainerManagerTest.class);

    @Test
    void tryStartReportsUnavailableInsteadOfThrowingWhenNoDockerDaemonIsReachable() {
        // Floci running inside Docker without a mounted daemon socket: the DocumentDB control
        // plane must keep working, so the failure is reported rather than propagated.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(lifecycleManager.isDockerReachable()).thenReturn(false);

        DocDbContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", null),
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
                .thenThrow(new RuntimeException("no such image: mongo:7.0"));
        when(lifecycleManager.isDockerReachable()).thenReturn(true);

        DocDbContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", null));
        assertEquals("no such image: mongo:7.0", failure.getMessage());
    }

    @Test
    void tryStartReturnsTheHandleOnceADaemonIsReachable() throws IOException, InterruptedException {
        // Real loopback socket standing in for the Mongo backend's port: the manager's
        // readiness probe only needs a successful TCP connect.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[1]);
                } catch (IOException e) {
                    // Test teardown races the socket close; nothing left to assert on.
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(27017, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            DocDbContainerManager manager = newManager(lifecycleManager);

            DocDbContainerHandle handle = manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", null);

            assertEquals("container-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
            acceptor.join(5000);
        }
    }

    @Test
    void startLabelsContainerWithResourceIdentity() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[1]);
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(27017,
                            new ContainerLifecycleManager.EndpointInfo(
                                    "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(new ContainerSpec("test-image"));

            ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
            lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.DocDbServiceConfig docdb = mock(EmulatorConfig.DocDbServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.docdb()).thenReturn(docdb);
            when(docdb.dockerNetwork()).thenReturn(Optional.empty());

            DocDbContainerManager manager = new DocDbContainerManager(containerBuilder, lifecycleManager,
                    logStreamer, mock(ContainerDetector.class), config,
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "mongo:7.0", "admin", "secret", "eu-west-1", null);

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "docdb",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "eu-west-1"));
        }
    }

    @Test
    void sameNamedClustersHaveIndependentContainersAcrossAccountsAndRegions() throws IOException {
        try (ServerSocket backend = new ServerSocket(0)) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            List<String> names = new ArrayList<>();
            doAnswer(call -> {
                names.add(call.getArgument(0));
                return null;
            }).when(lifecycle).removeIfExists(anyString());
            when(lifecycle.createAndStart(any())).thenAnswer(call ->
                    new ContainerLifecycleManager.ContainerInfo("container-" + names.size(), Map.of(27017,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))));
            RegionResolver resolver = mock(RegionResolver.class);
            DocDbContainerManager manager = newManager(lifecycle, resolver);
            List<DocDbContainerHandle> handles = new ArrayList<>();
            for (String account : List.of("111111111111", "222222222222")) {
                when(resolver.getAccountId()).thenReturn(account);
                for (String region : List.of("us-east-1", "eu-west-1")) {
                    handles.add(manager.start("shared", "mongo:7.0", "admin", "secret", region, null));
                }
            }
            assertEquals(4, names.stream().distinct().count());
            assertEquals("floci-aws-docdb-111111111111-us-east-1-shared", names.getFirst());
            // Delete reconstructs the handle from persisted metadata, outside its original scope.
            DocDbContainerHandle first = handles.getFirst();
            manager.stop(new DocDbContainerHandle(first.getContainerId(), "shared", first.getHost(), first.getPort()));
            verify(lifecycle, never()).stopAndRemove(eq(handles.get(1).getContainerId()), any());
            manager.stopAll();
            for (DocDbContainerHandle handle : handles) {
                verify(lifecycle, times(1)).stopAndRemove(handle.getContainerId(), handle.getLogStream());
            }
        }
    }

    @Test
    void aClusterWithAVolumeKeepsItsDataInANamedVolumeOfItsOwn() throws IOException {
        try (ServerSocket backend = new ServerSocket(0)) {
            ContainerLifecycleManager lifecycle = backedBy(backend);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            DocDbContainerManager manager = newManager(lifecycle, builder, storage("persistent", null, false));

            manager.start("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", "vol-1");

            verify(lifecycle).ensureVolume("floci-aws-docdb-vol-1");
            verify(builder).withNamedVolume("floci-aws-docdb-vol-1", "/data/db");
            verify(builder, never()).withBind(anyString(), anyString());
        }
    }

    @Test
    void aClusterWithAVolumeKeepsItsDataUnderTheHostPersistentPath(@TempDir Path hostPath) throws IOException {
        try (ServerSocket backend = new ServerSocket(0)) {
            ContainerLifecycleManager lifecycle = backedBy(backend);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            DocDbContainerManager manager = newManager(lifecycle, builder,
                    storage("persistent", hostPath.toString(), false));

            manager.start("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", "vol-1");

            Path dataDir = hostPath.resolve("docdb").resolve("vol-1");
            verify(builder).withBind(dataDir.toString(), "/data/db");
            assertTrue(Files.isDirectory(dataDir));
            verify(builder, never()).withNamedVolume(anyString(), anyString());
            verify(lifecycle, never()).ensureVolume(anyString());
        }
    }

    @Test
    void aClusterWithoutAVolumeMountsNothing() throws IOException {
        try (ServerSocket backend = new ServerSocket(0)) {
            ContainerLifecycleManager lifecycle = backedBy(backend);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            DocDbContainerManager manager = newManager(lifecycle, builder, storage("memory", null, false));

            manager.start("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", null);

            verify(builder, never()).withNamedVolume(anyString(), anyString());
            verify(builder, never()).withBind(anyString(), anyString());
            verify(lifecycle, never()).ensureVolume(anyString());
        }
    }

    @Test
    void aStartThatCannotFinishRemovesTheContainerItStarted() throws IOException {
        try (ServerSocket backend = new ServerSocket(0)) {
            ContainerLifecycleManager lifecycle = backedBy(backend);
            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(new ContainerSpec("test-image"));
            ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
            when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
            when(logStreamer.attachPolled(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("log stream refused"));
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.DocDbServiceConfig docdb = mock(EmulatorConfig.DocDbServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.docdb()).thenReturn(docdb);
            when(docdb.dockerNetwork()).thenReturn(Optional.empty());
            EmulatorConfig.StorageConfig storage = storage("persistent", null, false);
            when(config.storage()).thenReturn(storage);
            DocDbContainerManager manager = new DocDbContainerManager(containerBuilder, lifecycle, logStreamer,
                    mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));

            assertThrows(IllegalStateException.class,
                    () -> manager.start("cluster1", "mongo:7.0", "admin", "secret", "us-east-1", "vol-1"));

            verify(lifecycle).stopAndRemove("container-id", null);
            manager.stopAll();
            verify(lifecycle, times(1)).stopAndRemove(anyString(), any());
            verify(lifecycle, never()).removeVolume(anyString());
        }
    }

    @Test
    void removingStorageDropsTheVolumeWhenVolumesArePrunedOnDelete() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        DocDbContainerManager manager = newManager(lifecycle, mock(ContainerBuilder.Builder.class),
                storage("persistent", null, true));

        manager.removeStorage("vol-1");

        verify(lifecycle).removeVolume("floci-aws-docdb-vol-1");
    }

    @Test
    void removingStorageKeepsTheVolumeWhenAPersistentModeRetainsVolumes() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        DocDbContainerManager manager = newManager(lifecycle, mock(ContainerBuilder.Builder.class),
                storage("persistent", null, false));

        manager.removeStorage("vol-1");

        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void removingStorageLeavesAHostDirectoryAndAClusterWithoutAVolumeAlone(@TempDir Path hostPath) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", hostPath.toString(), true))
                .removeStorage("vol-1");
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", null, true))
                .removeStorage(null);

        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void discardingStorageDropsTheVolumeEvenWhenThePolicyRetainsVolumes(@TempDir Path hostPath) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        DocDbContainerManager manager = newManager(lifecycle, mock(ContainerBuilder.Builder.class),
                storage("persistent", null, false));

        manager.discardStorage("vol-1");
        manager.discardStorage(null);
        newManager(lifecycle, mock(ContainerBuilder.Builder.class), storage("persistent", hostPath.toString(), false))
                .discardStorage("vol-2");

        verify(lifecycle).removeVolume("floci-aws-docdb-vol-1");
        verify(lifecycle, times(1)).removeVolume(anyString());
    }

    private static ContainerLifecycleManager backedBy(ServerSocket backend) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(27017, new ContainerLifecycleManager.EndpointInfo(
                        "127.0.0.1", backend.getLocalPort()))));
        return lifecycle;
    }

    private static EmulatorConfig.StorageConfig storage(String mode, String hostPersistentPath, boolean prune) {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.mode()).thenReturn(mode);
        when(storage.hostPersistentPath()).thenReturn(hostPersistentPath);
        when(storage.pruneVolumesOnDelete()).thenReturn(prune);
        return storage;
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        return newManager(lifecycleManager, new RegionResolver("us-east-1", "000000000000"));
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager, RegionResolver resolver) {
        return newManager(lifecycleManager, resolver, mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF), null);
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager,
                                             ContainerBuilder.Builder builder,
                                             EmulatorConfig.StorageConfig storage) {
        return newManager(lifecycleManager, new RegionResolver("us-east-1", "000000000000"), builder, storage);
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager, RegionResolver resolver,
                                             ContainerBuilder.Builder builder,
                                             EmulatorConfig.StorageConfig storage) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        lenient().when(logStreamer.attachPolled(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> mock(Closeable.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.DocDbServiceConfig docdb = mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.docdb()).thenReturn(docdb);
        when(docdb.dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage()).thenReturn(storage);

        return new DocDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, resolver);
    }
}
