package io.floci.aws.services.docdb.container;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertNull(manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1"),
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
                () -> manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1"));
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

            DocDbContainerHandle handle = manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", "us-east-1");

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

            manager.start("cluster1", "mongo:7.0", "admin", "secret", "eu-west-1");

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
                    handles.add(manager.start("shared", "mongo:7.0", "admin", "secret", region));
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

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        return newManager(lifecycleManager, new RegionResolver("us-east-1", "000000000000"));
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager, RegionResolver resolver) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
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

        return new DocDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, resolver);
    }
}
