package io.github.hectorvent.floci.services.ecr.registry;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.HostBlindPortAllocator;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Several emulators sharing one Docker daemon and one ECR port range, whose allocators cannot see
 * each other's ports: what two starts racing between the probe and {@code docker run} look like,
 * so only Docker's bind conflict tells them apart.
 */
@QuarkusTest
class EcrRegistryPortConflictDockerIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ContainerLogStreamer logStreamer;

    @Inject
    ContainerDetector containerDetector;

    @Inject
    CurrentContainerNetworkResolver networkResolver;

    @Inject
    RegionResolver regionResolver;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for the ECR port conflict test");
    }

    @Test
    void registriesCompetingForOneRangeStartOnDifferentPortsUntilItIsExhausted() throws InterruptedException {
        int port = freePortPair();
        EmulatorConfig configA = config(port);
        EmulatorConfig configB = config(port);
        EmulatorConfig configC = config(port);
        PortAllocator allocatorB = new HostBlindPortAllocator();
        PortAllocator allocatorC = new HostBlindPortAllocator();
        EcrRegistryManager a = manager(configA, new HostBlindPortAllocator());
        EcrRegistryManager b = manager(configB, allocatorB);
        EcrRegistryManager c = manager(configC, allocatorC);
        try {
            a.ensureStarted();
            b.ensureStarted();

            assertEquals(port, a.effectivePort());
            assertEquals(port + 1, b.effectivePort());
            assertTrue(isReachable(a) && isReachable(b), "both registries must answer on their own port");
            assertEquals(port, allocatorB.allocate(port, port + 1), "the refused port must be released");

            RuntimeException ex = assertThrows(RuntimeException.class, c::ensureStarted);
            assertTrue(ex.getMessage().contains("[" + port + ", " + (port + 1) + "]"), ex.getMessage());
            assertFalse(c.isStarted());
            assertTrue(lifecycleManager.findByName(
                    ContainerStorageHelper.dockerName(configC, "ecr-registry")).isEmpty(),
                    "a container Docker refused to start must be removed");
            assertEquals(port, allocatorC.allocate(port, port + 1), "refused ports must be released");
            assertEquals(port + 1, allocatorC.allocate(port, port + 1), "refused ports must be released");
        } finally {
            a.shutdown();
            b.shutdown();
            c.shutdown();
        }
    }

    private EcrRegistryManager manager(EmulatorConfig config, PortAllocator allocator) {
        return new EcrRegistryManager(containerBuilder, lifecycleManager, logStreamer, containerDetector,
                networkResolver, allocator, config, regionResolver);
    }

    private static EmulatorConfig config(int basePort) {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace())
                .thenReturn(Optional.of("ecrport" + UUID.randomUUID().toString().substring(0, 8)));
        // Empty host-persistent-path selects a named volume, which memory mode removes on shutdown.
        when(config.storage().hostPersistentPath()).thenReturn("");
        when(config.storage().mode()).thenReturn("memory");
        when(config.services().ecr().registryContainerName()).thenReturn("ecr-registry");
        when(config.services().ecr().registryImage()).thenReturn(TestImages.REGISTRY);
        when(config.services().ecr().registryBasePort()).thenReturn(basePort);
        when(config.services().ecr().registryMaxPort()).thenReturn(basePort + 1);
        when(config.services().ecr().dockerNetwork()).thenReturn(Optional.empty());
        return config;
    }

    private int freePortPair() {
        PortAllocator probe = new PortAllocator(dockerClient);
        int port = probe.allocate(15100, 15899);
        for (int next = probe.allocate(port + 1, 15899); next != port + 1; next = probe.allocate(next + 1, 15899)) {
            port = next;
        }
        return port;
    }

    private static boolean isReachable(EcrRegistryManager manager) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (manager.httpClient().ping()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception ignored) {
            // The assumption in requireDocker reports Docker as unavailable.
            return false;
        }
    }
}
