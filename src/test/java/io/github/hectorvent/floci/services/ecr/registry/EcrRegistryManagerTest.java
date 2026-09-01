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
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
import static org.mockito.Mockito.verify;
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
    private EmulatorConfig.DockerConfig docker;
    private EmulatorConfig.EcrServiceConfig ecr;
    private ContainerBuilder.Builder builder;
    private EcrRegistryManager manager;

    @BeforeEach
    void setUp() {
        portAllocator = new PortAllocator();

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());

        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        containerDetector = Mockito.mock(ContainerDetector.class);
        currentContainerNetworkResolver = Mockito.mock(CurrentContainerNetworkResolver.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
        docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(Mockito.mock(EmulatorConfig.ServicesConfig.class));
        when(config.services().ecr()).thenReturn(ecr);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
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
    void ensureStartedLabelsContainerWithResourceIdentity() {
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        manager.ensureStarted();

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ecr",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
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
    void tryEnsureStarted_reportsFailureInsteadOfThrowingWhenDockerIsUnreachable() {
        // Floci running inside Docker without a mounted daemon socket. The control plane
        // must keep working, so the failure is reported rather than propagated.
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon"));

        for (int attempt = 0; attempt < 6; attempt++) {
            assertFalse(manager.tryEnsureStarted(), "attempt " + attempt + " should report unavailable");
        }
        assertFalse(manager.isStarted());
    }

    @Test
    void tryEnsureStarted_reportsSuccessOnceTheRegistryStarts() {
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("0123456789abcdef", Map.of()));

        assertTrue(manager.tryEnsureStarted());
        assertTrue(manager.isStarted());
    }

    @Test
    void httpClient_usesRegistryContainerDnsWhenRunningInsideDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        assertEquals("http://" + REGISTRY_NAME + ":5000", manager.httpClient().baseUrl());
    }

    @Test
    void adoptUsesPublishedHostPortEvenWhenRunningInsideDocker() {
        // Regression: in container mode adopt()'s endpoint resolves to the registry's
        // internal port (5000); the advertised proxy endpoint must use the published
        // host binding instead, or docker login from the host daemon fails.
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        Container existing = Mockito.mock(Container.class);
        when(existing.getId()).thenReturn("0123456789abcdef");
        when(lifecycleManager.findByName(REGISTRY_NAME)).thenReturn(Optional.of(existing));
        when(lifecycleManager.adopt("0123456789abcdef", List.of(5000)))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("0123456789abcdef",
                        Map.of(5000, new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 5000)),
                        Map.of(5000, BASE_PORT + 1)));

        manager.ensureStarted();

        assertEquals(BASE_PORT + 1, manager.effectivePort());
        assertEquals("http://localhost:" + (BASE_PORT + 1), manager.getProxyEndpoint());
    }

    @Test
    void adoptKeepsConfiguredPortWhenNoPublishedBindingExists() {
        Container existing = Mockito.mock(Container.class);
        when(existing.getId()).thenReturn("0123456789abcdef");
        when(lifecycleManager.findByName(REGISTRY_NAME)).thenReturn(Optional.of(existing));
        when(lifecycleManager.adopt("0123456789abcdef", List.of(5000)))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("0123456789abcdef", Map.of()));

        manager.ensureStarted();

        assertEquals(BASE_PORT, manager.effectivePort());
    }

    @Test
    void httpClient_usesNamespacedRegistryContainerDnsWhenConfigured() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(docker.resourceNamespace()).thenReturn(Optional.of("run/one"));

        assertEquals("http://floci-run-one-test-ecr-registry:5000", manager.httpClient().baseUrl());
    }

    @Test
    void rewriteImageUri_matchesAwsEcrUri_rewritesToLocalRegistryAndStartsIt() {
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        String rewritten = manager.rewriteImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1");

        assertEquals("123456789012.dkr.ecr.us-east-1.localhost:" + BASE_PORT + "/backend-user:1", rewritten);
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void rewriteImageUri_pathStyleConfig_usesPathStyleUri() {
        when(ecr.uriStyle()).thenReturn("path");
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        String rewritten = manager.rewriteImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1");

        assertEquals("localhost:" + BASE_PORT + "/123456789012/us-east-1/backend-user:1", rewritten);
    }

    @Test
    void rewriteImageUri_nonEcrImage_passesThroughWithoutStartingRegistry() {
        String rewritten = manager.rewriteImageUri("nginx:latest");

        assertEquals("nginx:latest", rewritten);
        verify(lifecycleManager, Mockito.never()).createAndStart(any());
    }

    @Test
    void rewriteImageUri_nullImage_returnsNull() {
        assertNull(manager.rewriteImageUri(null));
    }
}
