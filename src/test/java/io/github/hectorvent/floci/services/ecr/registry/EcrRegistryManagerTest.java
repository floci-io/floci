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
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
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
    private static final String AWS_ECR_IMAGE = "123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1";

    private PortAllocator portAllocator;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private EmulatorConfig.DockerConfig docker;
    private EmulatorConfig.EcrServiceConfig ecr;
    private EmulatorConfig.StorageConfig storage;
    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private DockerClient dockerClient;
    private InspectImageCmd inspectImage;
    private EcrRegistryManager manager;

    @BeforeEach
    void setUp() {
        portAllocator = new PortAllocator();

        containerBuilder = Mockito.mock(ContainerBuilder.class);
        builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());
        dockerClient = Mockito.mock(DockerClient.class);
        inspectImage = Mockito.mock(InspectImageCmd.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectImage);
        Mockito.doThrow(new NotFoundException("No such image")).when(inspectImage).exec();

        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        containerDetector = Mockito.mock(ContainerDetector.class);
        currentContainerNetworkResolver = Mockito.mock(CurrentContainerNetworkResolver.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
        docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(Mockito.mock(EmulatorConfig.ServicesConfig.class));
        when(config.services().ecr()).thenReturn(ecr);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        // Empty host-persistent-path selects named-volume mode (no host bind-mount logic).
        when(storage.hostPersistentPath()).thenReturn("");
        when(storage.mode()).thenReturn("persistent");
        when(ecr.registryContainerName()).thenReturn(REGISTRY_NAME);
        when(ecr.registryImage()).thenReturn("registry:2");
        when(ecr.registryBasePort()).thenReturn(BASE_PORT);
        when(ecr.registryMaxPort()).thenReturn(MAX_PORT);
        when(ecr.dockerNetwork()).thenReturn(Optional.empty());
        when(ecr.preferLocalImages()).thenReturn(true);

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

    @Test
    void rewriteImageUri_imagePresentOnDaemon_returnsUnchangedWithoutStartingRegistry() {
        Mockito.doReturn(new InspectImageResponse()).when(inspectImage).exec();

        String rewritten = manager.rewriteImageUri(AWS_ECR_IMAGE);

        assertEquals(AWS_ECR_IMAGE, rewritten);
        verify(dockerClient).inspectImageCmd(AWS_ECR_IMAGE);
        verify(lifecycleManager, Mockito.never()).createAndStart(any());
    }

    @Test
    void rewriteImageUri_imagePresentOnDaemon_preferLocalImagesOff_rewritesWithoutInspecting() {
        when(ecr.preferLocalImages()).thenReturn(false);
        Mockito.doReturn(new InspectImageResponse()).when(inspectImage).exec();
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        String rewritten = manager.rewriteImageUri(AWS_ECR_IMAGE);

        assertEquals("123456789012.dkr.ecr.us-east-1.localhost:" + BASE_PORT + "/backend-user:1", rewritten);
        verify(dockerClient, Mockito.never()).inspectImageCmd(anyString());
    }

    @Test
    void rewriteImageUri_imageRegistryBaseConfigured_inspectsResolvedReference() {
        String mirrored = "ghcr.io/floci-io/mirror/" + AWS_ECR_IMAGE;
        when(containerBuilder.resolveImage(AWS_ECR_IMAGE)).thenReturn(mirrored);
        Mockito.doReturn(new InspectImageResponse()).when(inspectImage).exec();

        String rewritten = manager.rewriteImageUri(AWS_ECR_IMAGE);

        assertEquals(AWS_ECR_IMAGE, rewritten);
        verify(dockerClient).inspectImageCmd(mirrored);
        verify(dockerClient, Mockito.never()).inspectImageCmd(AWS_ECR_IMAGE);
        verify(lifecycleManager, Mockito.never()).createAndStart(any());
    }

    @Test
    void rewriteImageUri_imageRegistryBaseConfigured_unprefixedLocalImageDoesNotCount() {
        String mirrored = "ghcr.io/floci-io/mirror/" + AWS_ECR_IMAGE;
        when(containerBuilder.resolveImage(AWS_ECR_IMAGE)).thenReturn(mirrored);
        InspectImageCmd inspectUnprefixed = Mockito.mock(InspectImageCmd.class);
        Mockito.doReturn(new InspectImageResponse()).when(inspectUnprefixed).exec();
        when(dockerClient.inspectImageCmd(AWS_ECR_IMAGE)).thenReturn(inspectUnprefixed);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        String rewritten = manager.rewriteImageUri(AWS_ECR_IMAGE);

        assertEquals("123456789012.dkr.ecr.us-east-1.localhost:" + BASE_PORT + "/backend-user:1", rewritten);
        verify(dockerClient).inspectImageCmd(mirrored);
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void rewriteImageUri_daemonInspectFails_rewritesToLocalRegistry() {
        Mockito.doThrow(new DockerClientException("daemon unavailable")).when(inspectImage).exec();
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        String rewritten = manager.rewriteImageUri(AWS_ECR_IMAGE);

        assertEquals("123456789012.dkr.ecr.us-east-1.localhost:" + BASE_PORT + "/backend-user:1", rewritten);
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void pruneStorageRemovesTheRegistryAndVolumeWhenConfigured() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        manager.ensureStarted();

        manager.pruneStorage();

        verify(lifecycleManager).stopAndRemove(Mockito.eq("container-id"), Mockito.isNull());
        verify(lifecycleManager).removeVolume("floci-ecr-registry-data");
    }

    @Test
    void pruneStorageRetainsTheRegistryWhenVolumePruningIsDisabled() {
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        manager.ensureStarted();

        manager.pruneStorage();

        verify(lifecycleManager, Mockito.never()).stopAndRemove(anyString(), Mockito.isNull());
        verify(lifecycleManager, Mockito.never()).removeVolume(anyString());
    }

    @Test
    void shutdownRemovesTheRegistryVolumeWhenItIsNotRetained() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        manager.ensureStarted();

        manager.shutdown();

        verify(lifecycleManager).stopAndRemove(Mockito.eq("container-id"), Mockito.isNull());
        verify(lifecycleManager).removeVolume("floci-ecr-registry-data");
    }

    @Test
    void deleteRepositoryStorageBoundsCleanupAndPreservesNestedRepositories() {
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        ExecCreateCmd execCreate = Mockito.mock(ExecCreateCmd.class, Mockito.RETURNS_SELF);
        ExecCreateCmdResponse exec = Mockito.mock(ExecCreateCmdResponse.class);
        when(exec.getId()).thenReturn("exec-id");
        when(execCreate.exec()).thenReturn(exec);
        when(dockerClient.execCreateCmd("container-id")).thenReturn(execCreate);
        ExecStartCmd execStart = Mockito.mock(ExecStartCmd.class);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("exec-id")).thenReturn(execStart);
        InspectExecCmd inspect = Mockito.mock(InspectExecCmd.class);
        InspectExecResponse result = Mockito.mock(InspectExecResponse.class);
        when(result.getExitCodeLong()).thenReturn(0L);
        when(inspect.exec()).thenReturn(result);
        when(dockerClient.inspectExecCmd("exec-id")).thenReturn(inspect);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        manager.deleteRepositoryStorage("000000000000", "us-east-1", "team/app");

        verify(execCreate).withCmd("timeout", "-k", "1", "10", "rm", "-rf",
                "/var/lib/registry/docker/registry/v2/repositories/000000000000/us-east-1/team/app/_layers",
                "/var/lib/registry/docker/registry/v2/repositories/000000000000/us-east-1/team/app/_manifests",
                "/var/lib/registry/docker/registry/v2/repositories/000000000000/us-east-1/team/app/_uploads");
    }
}
