package io.floci.aws.services.elasticache.container;

import com.github.dockerjava.api.DockerClient;
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
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the backend container is configured with and without
 * {@code floci.services.elasticache.shell-less-backend}: the server flags go to the container as
 * arguments or through the image's {@code VALKEY_EXTRA_FLAGS} hook, never both.
 */
@ResourceLock(ParallelLocks.PORTS)
class ElastiCacheShellLessContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(ElastiCacheShellLessContainerManagerTest.class);

    private static final List<String> CLUSTER_FLAGS = List.of(
            "--cluster-enabled", "yes",
            "--cluster-announce-hostname", "localhost",
            "--cluster-preferred-endpoint-type", "hostname",
            "--cluster-announce-client-ipv4", "127.0.0.1",
            "--cluster-announce-port", "6379",
            "--cluster-announce-bus-port", "16379");

    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    private final EmulatorConfig.ElastiCacheServiceConfig elasticache =
            mock(EmulatorConfig.ElastiCacheServiceConfig.class);

    private ElastiCacheContainerManager manager(boolean shellLess) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.elasticache()).thenReturn(elasticache);
        when(elasticache.dockerNetwork()).thenReturn(Optional.empty());
        when(elasticache.shellLessBackend()).thenReturn(shellLess);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new ElastiCacheContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config, regionResolver);
    }

    /** Runs {@code start} against a fake backend that answers the readiness probe. */
    private void startAgainstFakeBackend(ElastiCacheContainerManager manager, List<String> flags)
            throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[1024]);
                    socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            when(lifecycleManager.createAndStart(any(ContainerSpec.class)))
                    .thenReturn(new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", serverSocket.getLocalPort()))));
            manager.start("my-group", "kividb/test", flags, "us-east-1");
        }
    }

    @Test
    void shellLessBackendPassesTheServerFlagsAsContainerArguments() throws IOException {
        startAgainstFakeBackend(manager(true), List.of());

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(builder).withCmd(command.capture());
        assertEquals(List.of("--port", "6379", "--protected-mode", "no", "--loglevel", "verbose"),
                command.getValue());
        verify(builder, never()).withEnv(anyString(), anyString());
    }

    @Test
    void shellLessBackendPassesClusterModeFlagsAsContainerArguments() throws IOException {
        startAgainstFakeBackend(manager(true), CLUSTER_FLAGS);

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(builder).withCmd(command.capture());
        List<String> expected = new ArrayList<>(
                List.of("--port", "6379", "--protected-mode", "no", "--loglevel", "verbose"));
        expected.addAll(CLUSTER_FLAGS);
        assertEquals(expected, command.getValue());
        verify(builder, never()).withEnv(anyString(), anyString());
    }

    @Test
    void shellLessBackendNeverEnablesTheDebugCommand() throws IOException {
        startAgainstFakeBackend(manager(true), CLUSTER_FLAGS);

        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(builder).withCmd(command.capture());
        assertFalse(command.getValue().contains("--enable-debug-command"));
    }

    @Test
    void theDefaultPathStillUsesTheValkeyExtraFlagsHook() throws IOException {
        startAgainstFakeBackend(manager(false), CLUSTER_FLAGS);

        verify(builder).withEnv("VALKEY_EXTRA_FLAGS", "--loglevel verbose " + String.join(" ", CLUSTER_FLAGS));
        verify(builder, never()).withCmd(any(List.class));
    }

    @Test
    void aSnapshotIsReadOnlyFromABackendThatIsRunning() {
        ElastiCacheContainerManager manager = manager(true);

        assertThrows(IllegalStateException.class, () -> manager.snapshot("no-such-container"));
    }

    /**
     * A server that answers PING and reports the given data directory and snapshot file name, on
     * as many connections as the start opens.
     */
    private ServerSocket fakeBackend(String directory, String fileName) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler = new Thread(() -> answer(socket, directory, fileName));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    LOG.debugv(e, "Fake backend closed during test teardown");
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return serverSocket;
    }

    private static void answer(Socket socket, String directory, String fileName) {
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String header;
            while ((header = in.readLine()) != null) {
                int count = Integer.parseInt(header.substring(1));
                StringBuilder request = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    in.readLine();
                    request.append(in.readLine()).append(' ');
                }
                String reply;
                if (request.toString().startsWith("PING")) {
                    reply = "+PONG\r\n";
                } else if (request.toString().endsWith("dbfilename ")) {
                    reply = fileName.isEmpty() ? "*0\r\n"
                            : "*2\r\n$10\r\ndbfilename\r\n$" + fileName.length() + "\r\n" + fileName + "\r\n";
                } else {
                    reply = "*2\r\n$3\r\ndir\r\n$" + directory.length() + "\r\n" + directory + "\r\n";
                }
                socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            }
        } catch (IOException e) {
            LOG.debugv(e, "Fake backend connection closed");
        }
    }

    private ElastiCacheContainerManager seededManager(ServerSocket backend, String configuredFileName) {
        return seededManager(backend, configuredFileName, true);
    }

    private ElastiCacheContainerManager seededManager(ServerSocket backend, String configuredFileName,
                                                      boolean shellLess) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.inspectContainerCmd(anyString()).exec().getConfig().getWorkingDir()).thenReturn("/data");
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        when(lifecycleManager.createAndStart(any(ContainerSpec.class), any(Consumer.class))).thenAnswer(inv -> {
            Consumer<String> beforeStart = inv.getArgument(1);
            beforeStart.accept("container-id");
            return new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(6379,
                    new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort())));
        });
        ElastiCacheContainerManager manager = manager(shellLess);
        when(elasticache.shellLessBackendDbfilename()).thenReturn(configuredFileName);
        return manager;
    }

    // Catches: the default path loading a snapshot with DEBUG RELOAD, which needs
    // --enable-debug-command, a flag a Redis before 7 refuses to start with.
    @Test
    void theDefaultPathPlacesTheSnapshotBeforeTheServerStartsWithoutTheDebugCommand() throws IOException {
        try (ServerSocket backend = fakeBackend("/data", "dump.rdb")) {
            ElastiCacheContainerManager manager = seededManager(backend, "dump.rdb", false);

            manager.start("my-group", "valkey/valkey:8", List.of(), "us-east-1", "UkVESVM=");

            verify(lifecycleManager.getDockerClient()).copyArchiveToContainerCmd("container-id");
            verify(builder).withEnv("VALKEY_EXTRA_FLAGS", "--loglevel verbose");
            verify(builder, never()).withCmd(any(List.class));
        }
    }

    @Test
    void aRestoreIsConfirmedWhenTheServerLoadsTheFileThatWasPlaced() throws IOException {
        try (ServerSocket backend = fakeBackend("/data", "dump.rdb")) {
            ElastiCacheContainerManager manager = seededManager(backend, "dump.rdb");

            manager.start("my-group", "kividb/test", List.of(), "us-east-1", "UkVESVM=");
        }
    }

    @Test
    void aRestoreIsRefusedWhenTheServerLoadsAnotherFileName() throws IOException {
        try (ServerSocket backend = fakeBackend("/data", "dump.kdb")) {
            ElastiCacheContainerManager manager = seededManager(backend, "dump.rdb");

            ElastiCacheContainerManager.SnapshotRestoreException failure = assertThrows(
                    ElastiCacheContainerManager.SnapshotRestoreException.class,
                    () -> manager.start("my-group", "kividb/test", List.of(), "us-east-1", "UkVESVM="));

            assertTrue(failure.getMessage().contains("dump.kdb"), failure.getMessage());
        }
    }

    @Test
    void aRestoreIsRefusedWhenTheServerUsesAnotherDirectory() throws IOException {
        try (ServerSocket backend = fakeBackend("/elsewhere", "")) {
            ElastiCacheContainerManager manager = seededManager(backend, "dump.rdb");

            assertThrows(ElastiCacheContainerManager.SnapshotRestoreException.class,
                    () -> manager.start("my-group", "kividb/test", List.of(), "us-east-1", "UkVESVM="));
        }
    }

    @Test
    void aServerThatReportsNoFileNameIsAcceptedWhenTheDirectoryMatches() throws IOException {
        try (ServerSocket backend = fakeBackend("/data", "")) {
            ElastiCacheContainerManager manager = seededManager(backend, "dump.kdb");

            manager.start("my-group", "kividb/test", List.of(), "us-east-1", "UkVESVM=");
        }
    }
}
