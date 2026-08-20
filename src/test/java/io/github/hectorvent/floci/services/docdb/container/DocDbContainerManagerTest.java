package io.github.hectorvent.floci.services.docdb.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import com.github.dockerjava.api.DockerClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        when(lifecycleManager.getDockerClient()).thenThrow(
                new RuntimeException("java.net.SocketException: No such file or directory"));

        DocDbContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "mongo:7.0", "admin", "secret"),
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
        DockerClient dockerClient = mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        DocDbContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "mongo:7.0", "admin", "secret"));
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

            DocDbContainerHandle handle = manager.tryStart("cluster1", "mongo:7.0", "admin", "secret");

            assertEquals("container-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
            acceptor.join(5000);
        }
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.DocDbServiceConfig docdb = mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.docdb()).thenReturn(docdb);
        when(docdb.dockerNetwork()).thenReturn(Optional.empty());

        return new DocDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));
    }
}
