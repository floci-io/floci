package io.github.hectorvent.floci.services.msk;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedpandaManagerTest {

    private static final int KAFKA_PORT = 9092;

    @Mock
    private ContainerLifecycleManager lifecycleManager;
    @Mock
    private ContainerLogStreamer logStreamer;
    @Mock
    private ContainerDetector containerDetector;
    @Mock
    private RegionResolver regionResolver;
    @Mock
    private PortAllocator portAllocator;
    @Mock
    private EmulatorConfig config;
    @Mock
    private DockerHostResolver dockerHostResolver;
    @Mock
    private EmbeddedDnsServer embeddedDnsServer;

    @TempDir
    Path tempDir;

    RedpandaManager manager;

    private HttpServer adminServer;

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.MskServiceConfig msk = mock(EmulatorConfig.MskServiceConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);

        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.msk()).thenReturn(msk);
        lenient().when(services.dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(msk.defaultImage()).thenReturn("redpandadata/redpanda:latest");
        lenient().when(msk.kafkaHostPortBase()).thenReturn(9300);
        lenient().when(msk.kafkaHostPortMax()).thenReturn(9399);
        lenient().when(config.docker()).thenReturn(docker);
        lenient().when(docker.logMaxSize()).thenReturn("10m");
        lenient().when(docker.logMaxFile()).thenReturn("3");
        lenient().when(config.storage()).thenReturn(storage);
        lenient().when(storage.hostPersistentPath()).thenReturn(tempDir.toAbsolutePath().toString());

        lenient().when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        manager = new RedpandaManager(containerBuilder, lifecycleManager, logStreamer, containerDetector,
                config, regionResolver, portAllocator);

        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        lenient().when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
    }

    @AfterEach
    void tearDown() {
        if (adminServer != null) {
            adminServer.stop(0);
        }
    }

    private MskCluster newCluster() {
        MskCluster cluster = new MskCluster("arn:aws:kafka:us-east-1:000000000000:cluster/test-cluster/abc", "test-cluster", "3.6.0");
        cluster.setVolumeId("abc123");
        return cluster;
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

        MskCluster cluster = newCluster();
        cluster.setContainerId("container-id");
        cluster.setBootstrapBrokers("localhost:19092");

        assertTrue(manager.isReady(cluster),
                "isReady() should report ready once /v1/status/ready answers 200; "
                        + "if it regresses to polling /ready (which always 404s), this assertion fails");
    }

    @Test
    void nativeModeAdvertisesPreAllocatedLocalhostAddress() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(portAllocator.allocate(9300, 9399)).thenReturn(54321);

        ContainerInfo info = new ContainerInfo("container-123",
                Map.of(KAFKA_PORT, new EndpointInfo("localhost", 54321)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);

        manager.startContainer(newCluster());

        verify(lifecycleManager).createAndStart(specCaptor.capture());
        ContainerSpec spec = specCaptor.getValue();

        int flagIndex = spec.cmd().indexOf("--advertise-kafka-addr");
        assertTrue(flagIndex >= 0, "cmd should contain --advertise-kafka-addr");
        assertEquals("localhost:54321", spec.cmd().get(flagIndex + 1));

        assertEquals(Integer.valueOf(54321), spec.portBindings().get(KAFKA_PORT));
    }

    @Test
    void containerModeAdvertisesContainerNameAddress() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        ContainerInfo info = new ContainerInfo("container-456",
                Map.of(KAFKA_PORT, new EndpointInfo("172.18.0.5", KAFKA_PORT)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);

        manager.startContainer(newCluster());

        verify(lifecycleManager).createAndStart(specCaptor.capture());
        ContainerSpec spec = specCaptor.getValue();

        int flagIndex = spec.cmd().indexOf("--advertise-kafka-addr");
        assertTrue(flagIndex >= 0, "cmd should contain --advertise-kafka-addr");
        assertEquals("floci-msk-abc123:9092", spec.cmd().get(flagIndex + 1));

        assertFalse(spec.portBindings().containsKey(KAFKA_PORT), "container mode should not publish ports to host");
        assertTrue(spec.exposedPorts().contains(KAFKA_PORT));
        assertTrue(spec.exposedPorts().contains(RedpandaManager.ADMIN_PORT));
        assertFalse(Files.exists(tempDir.resolve("msk").resolve("test-cluster")),
                "container mode must not create host directories from inside the emulator container");

        verifyNoInteractions(portAllocator);
    }

    @Test
    void stopContainerReleasesAllocatedKafkaHostPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        MskCluster cluster = newCluster();
        cluster.setContainerId("container-id");
        cluster.setBootstrapBrokers("localhost:54321");

        manager.stopContainer(cluster);

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(54321);
    }
}
