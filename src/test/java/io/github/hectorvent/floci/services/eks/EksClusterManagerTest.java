package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EksClusterManagerTest {

    @Test
    void webhookKubeconfigEmbedsServerUrl() {
        String url = "http://host.docker.internal:4566/_floci/eks/token-webhook";
        String yaml = EksClusterManager.buildWebhookKubeconfig(url);

        assertTrue(yaml.contains("kind: Config"), "should be a kubeconfig");
        assertTrue(yaml.contains("server: " + url), "should point the webhook at Floci");
        assertTrue(yaml.contains("current-context: floci-token-webhook"),
                "should select the webhook context");
    }

    @Test
    void webhookKubeconfigUsesContainerNetworkAddress() {
        String url = "http://172.18.0.5:4566/_floci/eks/token-webhook";
        String yaml = EksClusterManager.buildWebhookKubeconfig(url);
        assertTrue(yaml.contains("server: " + url));
    }

    @Test
    void hostModeAlwaysReturnsHostReachableEndpoint() {
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(true, "host", "floci-eks-demo", 6500));
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(false, "host", "floci-eks-demo", 6500));
    }

    @Test
    void networkModeReturnsContainerDnsOnlyInContainer() {
        assertEquals("https://floci-eks-demo:6443",
                EksClusterManager.resolvePublicEndpoint(true, "network", "floci-eks-demo", 6500));
        // Native mode has no usable container DNS name — falls back to the host endpoint.
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(false, "network", "floci-eks-demo", 6500));
    }

    @Test
    void endpointModeIsCaseInsensitiveAndDefaultsToHost() {
        assertEquals("https://floci-eks-demo:6443",
                EksClusterManager.resolvePublicEndpoint(true, "NETWORK", "floci-eks-demo", 6500));
        // Unknown / unset modes behave as host.
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(true, "bogus", "floci-eks-demo", 6500));
    }

    @Test
    void registriesYamlMirrorsEveryRegionHostnameAndThePathStyleForm() {
        String yaml = EksClusterManager.buildRegistriesYaml(
                "000000000000", AwsRegions.ALL, 5100, "http://floci-ecr-registry:5000");

        assertTrue(yaml.startsWith("mirrors:\n"));
        for (String region : AwsRegions.ALL) {
            assertTrue(yaml.contains("\"000000000000.dkr.ecr." + region + ".localhost:5100\":"),
                    "should mirror the " + region + " hostname");
        }
        assertTrue(yaml.contains("\"localhost:5100\":"), "should mirror the path-style form");
        assertFalse(yaml.contains("\"*\""), "must not catch-all public registries");
        long endpoints = yaml.lines().filter(l -> l.contains("- \"http://floci-ecr-registry:5000\"")).count();
        assertEquals(AwsRegions.ALL.size() + 1, endpoints,
                "every mirror should point at the registry's in-network endpoint");
    }

    @Test
    void registriesYamlUsesTheActualRegistryPortAndEndpoint() {
        String yaml = EksClusterManager.buildRegistriesYaml(
                "111122223333", List.of("eu-central-1"), 5142, "http://my-registry:5000");

        assertTrue(yaml.contains("\"111122223333.dkr.ecr.eu-central-1.localhost:5142\":"));
        assertTrue(yaml.contains("\"localhost:5142\":"));
        assertTrue(yaml.contains("- \"http://my-registry:5000\""));
    }

    @Test
    void serverArgsOmitCniFlagsByDefault() {
        List<String> args = EksClusterManager.buildServerArgs(false);

        assertTrue(args.contains("--disable=traefik"));
        assertFalse(args.contains("--flannel-backend=none"));
        assertFalse(args.contains("--disable-network-policy"));
        assertFalse(args.contains("--disable-kube-proxy"));
    }

    @Test
    void serverArgsDisableFlannelAndKubeProxyWhenRequested() {
        List<String> args = EksClusterManager.buildServerArgs(true);

        assertTrue(args.contains("--flannel-backend=none"));
        assertTrue(args.contains("--disable-network-policy"));
        assertTrue(args.contains("--disable-kube-proxy"));
        // Base args must still be present — disableCni only adds flags, never replaces them.
        assertTrue(args.contains("--disable=traefik"));
        assertTrue(args.contains("--tls-san=localhost"));
    }

    @Test
    void rshareEntrypointIsPosixShCompatible() {
        String script = EksClusterManager.RSHARE_ENTRYPOINT.get(2);

        assertEquals(List.of("sh", "-c"), EksClusterManager.RSHARE_ENTRYPOINT.subList(0, 2));
        assertTrue(script.contains("mount --make-rshared /"));
        assertTrue(script.contains("exec /bin/k3s"));
        assertFalse(script.contains("bash"), "the k3s image has no bash, only busybox sh");
    }

    @Test
    void rshareEntrypointWarnsOnStderrWhenMountFails() {
        String script = EksClusterManager.RSHARE_ENTRYPOINT.get(2);

        // A failed mount must be surfaced, not silently swallowed, so a later CNI failure
        // is traceable back to this step instead of looking unrelated.
        assertTrue(script.contains("|| echo"));
        assertTrue(script.contains(">&2"));
        assertFalse(script.contains("2>/dev/null"));
    }

    @Test
    void rshareWrappedCmdPreservesServerArgsAfterThePlaceholder() {
        List<String> serverArgs = EksClusterManager.buildServerArgs(true);
        List<String> wrapped = EksClusterManager.buildRshareWrappedCmd(serverArgs);

        // First element is an unused $0 placeholder consumed by sh -c, not part of serverArgs.
        assertEquals(serverArgs, wrapped.subList(1, wrapped.size()));
    }

    @Test
    void startClusterLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.EksServiceConfig eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.eks()).thenReturn(eks);
        when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
        when(eks.apiServerBasePort()).thenReturn(6440);
        when(eks.apiServerMaxPort()).thenReturn(6499);
        when(eks.dockerNetwork()).thenReturn(Optional.empty());
        when(eks.disableCni()).thenReturn(false);
        when(eks.iamAuthWebhook()).thenReturn(false);
        when(eks.ecrRegistryMirror()).thenReturn(false);

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(
                new ContainerInfo("container-id", Map.of()));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        EksClusterManager manager = new EksClusterManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                config, regionResolver);

        Cluster cluster = new Cluster();
        cluster.setName("my-cluster");

        manager.startCluster(cluster);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "eks",
                "io.floci.resource-id", "my-cluster",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    /** Mirror-injection guard behavior, without a Docker daemon. */
    @Nested
    class InjectEcrRegistryMirror {

        @TempDir
        Path tempDir;

        private ContainerLifecycleManager lifecycleManager;
        private DockerClient dockerClient;
        private CopyArchiveToContainerCmd copyCmd;
        private EcrRegistryManager registryManager;
        private EmulatorConfig.EksServiceConfig eks;
        private EmulatorConfig.EcrServiceConfig ecr;
        private EksClusterManager manager;

        @BeforeEach
        void setUp() {
            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            dockerClient = Mockito.mock(DockerClient.class);
            copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);

            registryManager = Mockito.mock(EcrRegistryManager.class);
            when(registryManager.effectivePort()).thenReturn(5100);
            when(registryManager.internalEndpoint()).thenReturn("http://floci-ecr-registry:5000");

            EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
            when(config.services()).thenReturn(Mockito.mock(EmulatorConfig.ServicesConfig.class));
            when(config.services().eks()).thenReturn(eks);
            when(config.services().ecr()).thenReturn(ecr);
            when(config.defaultRegion()).thenReturn("us-east-1");
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(eks.ecrRegistryMirror()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());
            when(ecr.enabled()).thenReturn(true);

            manager = new EksClusterManager(
                    Mockito.mock(ContainerBuilder.class), lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    Mockito.mock(DockerHostResolver.class), registryManager, config,
                    Mockito.mock(RegionResolver.class));
        }

        @Test
        void injectsTheMirrorIntoTheContainer() {
            manager.injectEcrRegistryMirror("container-1", "demo");

            verify(registryManager).ensureStarted();
            verify(copyCmd).withRemotePath("/etc");
            verify(copyCmd).exec();
        }

        @Test
        void skipsWhenTheKnobIsOff() {
            when(eks.ecrRegistryMirror()).thenReturn(false);

            manager.injectEcrRegistryMirror("container-1", "demo");

            verifyNoInteractions(registryManager);
            verify(lifecycleManager, never()).getDockerClient();
        }

        @Test
        void skipsWhenEcrIsDisabled() {
            when(ecr.enabled()).thenReturn(false);

            manager.injectEcrRegistryMirror("container-1", "demo");

            verifyNoInteractions(registryManager);
            verify(lifecycleManager, never()).getDockerClient();
        }

        @Test
        void registryStartupFailureSkipsTheMirrorWithoutAborting() {
            Mockito.doThrow(new RuntimeException("no docker")).when(registryManager).ensureStarted();

            manager.injectEcrRegistryMirror("container-1", "demo");

            verify(lifecycleManager, never()).getDockerClient();
        }

        @Test
        void copyFailureDoesNotAbortClusterCreation() {
            when(copyCmd.exec()).thenThrow(new RuntimeException("copy failed"));

            manager.injectEcrRegistryMirror("container-1", "demo");

            verify(copyCmd).exec();
        }
    }
}
