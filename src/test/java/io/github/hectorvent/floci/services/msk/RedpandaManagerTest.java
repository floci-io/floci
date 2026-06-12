package io.github.hectorvent.floci.services.msk;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedpandaManagerTest {

    @Mock
    private ContainerBuilder containerBuilder;
    @Mock
    private ContainerLifecycleManager lifecycleManager;
    @Mock
    private ContainerLogStreamer logStreamer;
    @Mock
    private ContainerDetector containerDetector;
    @Mock
    private EmulatorConfig config;
    @Mock
    private RegionResolver regionResolver;

    private HttpServer adminServer;

    @AfterEach
    void tearDown() {
        if (adminServer != null) {
            adminServer.stop(0);
        }
    }

    private static MskCluster newCluster() {
        return new MskCluster("arn:aws:kafka:us-east-1:000000000000:cluster/test-cluster/abc", "test-cluster", "3.6.0");
    }

    private int startFakeAdminServer() throws Exception {
        adminServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        adminServer.createContext("/v1/status/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        adminServer.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        adminServer.start();
        return adminServer.getAddress().getPort();
    }

    @Test
    void isReadyPollsTheCorrectAdminReadinessPathInNativeMode() throws Exception {
        int adminHostPort = startFakeAdminServer();

        when(containerDetector.isRunningInContainer()).thenReturn(false);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);

        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings().getPorts().getBindings()).thenReturn(Map.of(
                ExposedPort.tcp(RedpandaManager.ADMIN_PORT),
                new Ports.Binding[] { new Ports.Binding("0.0.0.0", String.valueOf(adminHostPort)) }));

        RedpandaManager manager = new RedpandaManager(
                containerBuilder, lifecycleManager, logStreamer, containerDetector, config, regionResolver);

        MskCluster cluster = newCluster();
        cluster.setContainerId("container-id");
        cluster.setBootstrapBrokers("localhost:19092");

        assertTrue(manager.isReady(cluster),
                "isReady() should report ready once /v1/status/ready answers 200; "
                        + "if it regresses to polling /ready (which always 404s), this assertion fails");
    }
}
