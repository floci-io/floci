package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(ElastiCacheContainerManagerTest.class);

    @Test
    void stopByGroupIdRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ElastiCacheContainerManager manager = new ElastiCacheContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class));

        // No container was ever registered for this id (e.g. it failed before registration).
        // Rollback must still fall back to the deterministic name so nothing is orphaned.
        manager.stopByGroupId("my-group");

        verify(lifecycleManager).removeIfExists("floci-valkey-my-group");
    }

    @Test
    void startLabelsContainerWithResourceIdentity() throws IOException {
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

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo(
                                    "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mock(ContainerSpec.class));

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.ElastiCacheServiceConfig elasticache = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.elasticache()).thenReturn(elasticache);
            when(elasticache.dockerNetwork()).thenReturn(Optional.empty());

            RegionResolver regionResolver = mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

            ElastiCacheContainerManager manager = new ElastiCacheContainerManager(containerBuilder, lifecycleManager,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config, regionResolver);

            manager.start("my-group", "valkey/valkey:7.2");

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "elasticache",
                    "io.floci.resource-id", "my-group",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "us-east-1"));
        }
    }
}
