package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcrRegistryManager} startup behavior. Uses a real
 * {@link PortAllocator} and a mocked Docker layer so the failure path can be
 * exercised without a Docker daemon.
 */
class EcrRegistryManagerTest {

    private static final int BASE_PORT = 6100;
    private static final int MAX_PORT = 6101; // pool of exactly two ports
    private static final String REGISTRY_NAME = "floci-test-ecr-registry";

    private PortAllocator portAllocator;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private EcrRegistryManager manager;

    @BeforeEach
    void setUp() {
        portAllocator = new PortAllocator();

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder =
                Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());

        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        containerDetector = Mockito.mock(ContainerDetector.class);
        currentContainerNetworkResolver = Mockito.mock(CurrentContainerNetworkResolver.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.EcrServiceConfig ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(Mockito.mock(EmulatorConfig.ServicesConfig.class));
        when(config.services().ecr()).thenReturn(ecr);
        when(config.storage()).thenReturn(storage);
        // Empty host-persistent-path selects named-volume mode (no host bind-mount logic).
        when(storage.hostPersistentPath()).thenReturn("");
        when(ecr.registryContainerName()).thenReturn(REGISTRY_NAME);
        when(ecr.registryImage()).thenReturn("registry:2");
        when(ecr.registryBasePort()).thenReturn(BASE_PORT);
        when(ecr.registryMaxPort()).thenReturn(MAX_PORT);
        when(ecr.dockerNetwork()).thenReturn(Optional.empty());

        manager = new EcrRegistryManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, currentContainerNetworkResolver, portAllocator, config, regionResolver);
    }

    @Test
    void ensureStarted_releasesPortWhenDockerStartFails_soPoolIsNotExhausted() {
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon"));

        // Far more attempts than the two-port pool. Every attempt must surface the
        // real Docker failure. If the reserved port were leaked on failure, the pool
        // would exhaust after two attempts and later calls would instead fail with
        // "No free port available" — the symptom this test guards against.
        for (int attempt = 0; attempt < 6; attempt++) {
            RuntimeException ex = assertThrows(RuntimeException.class, manager::ensureStarted);
            assertTrue(ex.getMessage().contains("Failed to start ECR backing registry container"),
                    "attempt " + attempt + " should surface the Docker failure, got: " + ex.getMessage());
            assertFalse(ex.getMessage().contains("No free port available"),
                    "port pool leaked on attempt " + attempt + ": " + ex.getMessage());
        }
    }

    @Test
    void httpClient_usesRegistryContainerDnsWhenRunningInsideDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        assertEquals("http://" + REGISTRY_NAME + ":5000", manager.httpClient().baseUrl());
    }
}
