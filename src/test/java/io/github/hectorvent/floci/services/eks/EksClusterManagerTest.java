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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EksClusterManagerTest {

    @Test
    void workerWebhookBindsAccountRegionAndClusterIncarnation() {
        Cluster cluster = new Cluster();
        cluster.setName("demo");
        cluster.setAccountId("123456789012");
        cluster.setArn("arn:aws:eks:us-west-2:123456789012:cluster/demo");
        cluster.setCreatedAt(Instant.parse("2026-09-17T00:00:00Z"));
        assertEquals("/_floci/eks/clusters/demo/token-webhook/scope/123456789012"
                + "/us-west-2/2026-09-17T00:00:00Z", EksClusterManager.webhookPath(cluster));
    }

    @Test
    void webhookPathBindsAuthenticationToOneCluster() {
        assertEquals("/_floci/eks/clusters/demo/token-webhook", EksClusterManager.webhookPath("demo"));
    }

    @Test
    void webhookKubeconfigEmbedsServerUrl() {
        String url = "http://host.docker.internal:4566/_floci/eks/clusters/demo/token-webhook";
        String yaml = EksClusterManager.buildWebhookKubeconfig(url);

        assertTrue(yaml.contains("kind: Config"), "should be a kubeconfig");
        assertTrue(yaml.contains("server: " + url), "should point the webhook at Floci");
        assertTrue(yaml.contains("current-context: floci-token-webhook"),
                "should select the webhook context");
    }

    @Test
    void webhookKubeconfigUsesContainerNetworkAddress() {
        String url = "http://172.18.0.5:4566/_floci/eks/clusters/demo/token-webhook";
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
                "000000000000", AwsRegions.ALL, 4566, "http://floci:4566");

        assertTrue(yaml.startsWith("mirrors:\n"));
        for (String region : AwsRegions.ALL) {
            assertTrue(yaml.contains("\"000000000000.dkr.ecr." + region + ".localhost:4566\":"),
                    "should mirror the " + region + " hostname");
        }
        assertTrue(yaml.contains("\"localhost:4566\":"), "should mirror the path-style form");
        assertFalse(yaml.contains("\"*\""), "must not catch-all public registries");
        long endpoints = yaml.lines().filter(l -> l.contains("- \"http://floci:4566\"")).count();
        assertEquals(AwsRegions.ALL.size() + 1, endpoints,
                "every mirror should point at Floci's in-network data plane");
    }

    @Test
    void registriesYamlUsesTheActualRegistryPortAndEndpoint() {
        String yaml = EksClusterManager.buildRegistriesYaml(
                "111122223333", List.of("eu-central-1"), 4566, "http://floci:4566");

        assertTrue(yaml.contains("\"111122223333.dkr.ecr.eu-central-1.localhost:4566\":"));
        assertTrue(yaml.contains("\"localhost:4566\":"));
        assertTrue(yaml.contains("- \"http://floci:4566\""));
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

    /** Re-latching persisted clusters after a Floci/Docker restart (#2609), without a Docker daemon. */
    @Nested
    class RestoreCluster {

        private EmulatorConfig config;
        private EmulatorConfig.StorageConfig storage;
        private ContainerLifecycleManager lifecycleManager;
        private PortAllocator portAllocator;
        private EksClusterManager manager;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.EksServiceConfig eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
            when(config.services()).thenReturn(services);
            when(config.storage()).thenReturn(storage);
            when(services.eks()).thenReturn(eks);
            when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
            when(eks.apiServerBasePort()).thenReturn(6440);
            when(eks.apiServerMaxPort()).thenReturn(6499);
            when(eks.dockerNetwork()).thenReturn(Optional.empty());
            when(eks.endpointMode()).thenReturn("host");
            when(eks.disableCni()).thenReturn(false);
            when(eks.iamAuthWebhook()).thenReturn(false);
            when(eks.ecrRegistryMirror()).thenReturn(false);

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));
            portAllocator = Mockito.mock(PortAllocator.class);

            manager = new EksClusterManager(containerBuilder, lifecycleManager,
                    Mockito.mock(ContainerDetector.class), portAllocator,
                    Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                    config, Mockito.mock(RegionResolver.class));
        }

        // Container's getters are final, so survivors are built from JSON instead of mocked.
        private Container survivingContainer(String id) {
            return containerFromJson("{\"Id\":\"" + id + "\"}");
        }

        private Container survivingContainerOwnedBy(String id, String accountId) {
            return containerFromJson("{\"Id\":\"" + id + "\","
                    + "\"Labels\":{\"io.floci.account\":\"" + accountId + "\"}}");
        }

        private Container containerFromJson(String json) {
            try {
                return new ObjectMapper().readValue(json, Container.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        private Cluster cluster() {
            Cluster cluster = new Cluster();
            cluster.setName("demo");
            return cluster;
        }

        private void stubFreshStart(String containerId, int allocatedPort) {
            when(lifecycleManager.create(any())).thenReturn(containerId);
            when(lifecycleManager.startCreated(any(), any()))
                    .thenReturn(new ContainerInfo(containerId, Map.of()));
            when(portAllocator.allocate(6440, 6499)).thenReturn(allocatedPort);
        }

        @Test
        void adoptsASurvivingContainerAndKeepsItsPublishedPort() {
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainer("cid-1")));
            // adopt() starts a stopped container — the Docker-reboot case from #2609.
            when(lifecycleManager.adopt("cid-1", List.of(6443)))
                    .thenReturn(new ContainerInfo("cid-1", Map.of(), Map.of(6443, 6512)));

            Cluster cluster = cluster();
            manager.restoreCluster(cluster);

            assertEquals("cid-1", cluster.getContainerId());
            assertEquals(6512, cluster.getHostPort());
            assertEquals("https://localhost:6512", cluster.getEndpoint());
            assertEquals("https://localhost:6512", cluster.getInternalEndpoint());
            // The port Docker already holds must not be handed out to another cluster.
            verify(portAllocator).markReserved(6512);
            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void recreatesTheContainerWhenNoneSurvives() {
            when(lifecycleManager.findByName("floci-eks-demo")).thenReturn(Optional.empty());
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            manager.restoreCluster(cluster);

            assertEquals("cid-new", cluster.getContainerId());
            assertEquals(6440, cluster.getHostPort());
            verify(lifecycleManager).create(any());
            verify(lifecycleManager, never()).adopt(anyString(), any());
        }

        @Test
        void recreatesWhenTheSurvivingContainerPublishesNoPort() {
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainer("cid-1")));
            when(lifecycleManager.adopt("cid-1", List.of(6443)))
                    .thenReturn(new ContainerInfo("cid-1", Map.of()));
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            manager.restoreCluster(cluster);

            assertEquals("cid-new", cluster.getContainerId());
            verify(portAllocator, never()).markReserved(6440);
        }

        @Test
        void recreatesWhenAdoptionFails() {
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainer("cid-1")));
            when(lifecycleManager.adopt(anyString(), any())).thenThrow(new RuntimeException("broken"));
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            manager.restoreCluster(cluster);

            assertEquals("cid-new", cluster.getContainerId());
            // startCluster removes the broken survivor before reusing its name.
            verify(lifecycleManager).removeIfExists("floci-eks-demo");
        }

        @Test
        void stopClusterRetainsTheDataVolumeInPersistentStorageMode() {
            when(storage.mode()).thenReturn("hybrid");
            when(storage.pruneVolumesOnDelete()).thenReturn(false);

            Cluster cluster = cluster();
            cluster.setContainerId("cid-1");
            manager.stopCluster(cluster);

            verify(lifecycleManager).stopAndRemove("cid-1", null);
            // The volume must survive so restoreCluster can bring the workloads back.
            verify(lifecycleManager, never()).removeVolume(anyString());
        }

        @Test
        void stopClusterRemovesTheDataVolumeInMemoryStorageMode() {
            when(storage.mode()).thenReturn("memory");

            Cluster cluster = cluster();
            cluster.setContainerId("cid-1");
            manager.stopCluster(cluster);

            verify(lifecycleManager).stopAndRemove("cid-1", null);
            verify(lifecycleManager).removeVolume("floci-eks-demo");
        }

        @Test
        void nonDefaultAccountClustersGetAccountQualifiedDockerNames() {
            // Cluster names are unique only per account: an unqualified name would cross-bind two
            // accounts' same-named clusters to one container and data volume.
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(lifecycleManager.findByName("floci-eks-demo")).thenReturn(Optional.empty());
            when(lifecycleManager.findByName("floci-eks-999999999999.demo"))
                    .thenReturn(Optional.of(survivingContainer("cid-9")));
            when(lifecycleManager.adopt("cid-9", List.of(6443)))
                    .thenReturn(new ContainerInfo("cid-9", Map.of(), Map.of(6443, 6520)));

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.restoreCluster(cluster);

            assertEquals("cid-9", cluster.getContainerId());
            assertEquals("floci-eks-999999999999.demo", cluster.getDockerName());
        }

        @Test
        void defaultAccountClustersKeepTheHistoricalUnqualifiedName() {
            when(config.defaultAccountId()).thenReturn("000000000000");

            Cluster cluster = cluster();
            cluster.setAccountId("000000000000");

            assertEquals("floci-eks-demo", manager.clusterResourceName(cluster));
        }

        @Test
        void legacyNamedContainerIsKeptWhenItsAccountLabelMatchesTheOwner() {
            // A non-default-account cluster created before account-qualified naming left its
            // container (and mounted data volume) under floci-eks-<name>. Restoration must keep
            // that name — recreating under the qualified name would orphan the workloads.
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainerOwnedBy("cid-legacy", "999999999999")));
            when(lifecycleManager.adopt("cid-legacy", List.of(6443)))
                    .thenReturn(new ContainerInfo("cid-legacy", Map.of(), Map.of(6443, 6512)));

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.restoreCluster(cluster);

            assertEquals("cid-legacy", cluster.getContainerId());
            assertEquals("floci-eks-demo", cluster.getDockerName());
            verify(lifecycleManager, never()).create(any());
        }

        @Test
        void legacyNamedContainerOfAnotherAccountIsNeverClaimed() {
            // The legacy container belongs to whoever's label it carries. A different account's
            // restored cluster must start fresh under its qualified name, leaving the survivor
            // (and its data) untouched.
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainerOwnedBy("cid-other", "111111111111")));
            when(lifecycleManager.findByName("floci-eks-999999999999.demo")).thenReturn(Optional.empty());
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.restoreCluster(cluster);

            assertEquals("floci-eks-999999999999.demo", cluster.getDockerName());
            assertEquals("cid-new", cluster.getContainerId());
            verify(lifecycleManager, never()).adopt(anyString(), any());
            verify(lifecycleManager, never()).removeIfExists("floci-eks-demo");
        }

        @Test
        void legacyNamedContainerWithoutAnOwnerLabelIsNeverClaimed() {
            // No label means no verifiable owner — adopting on a guess could hand another
            // account's workloads over. The cluster starts fresh under its qualified name and
            // the unclaimed survivor is only reported.
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(lifecycleManager.findByName("floci-eks-demo"))
                    .thenReturn(Optional.of(survivingContainer("cid-unlabeled")));
            when(lifecycleManager.findByName("floci-eks-999999999999.demo")).thenReturn(Optional.empty());
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.restoreCluster(cluster);

            assertEquals("floci-eks-999999999999.demo", cluster.getDockerName());
            verify(lifecycleManager, never()).adopt(anyString(), any());
            verify(lifecycleManager, never()).removeIfExists("floci-eks-demo");
        }

        @Test
        void legacyVolumeWithoutItsContainerIsNeverClaimed() {
            // A surviving volume carries no ownership label at all, so it cannot be verified for
            // any account — the cluster starts fresh under its qualified name and the volume is
            // reported for manual migration instead of being silently mounted or orphaned.
            when(config.defaultAccountId()).thenReturn("000000000000");
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            InspectVolumeCmd inspectVolumeCmd = Mockito.mock(InspectVolumeCmd.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.inspectVolumeCmd("floci-eks-demo")).thenReturn(inspectVolumeCmd);
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.restoreCluster(cluster);

            assertEquals("floci-eks-999999999999.demo", cluster.getDockerName());
            assertEquals("cid-new", cluster.getContainerId());
            verify(inspectVolumeCmd).exec();
        }

        @Test
        void startClusterAssignsTheAccountQualifiedDockerName() {
            when(config.defaultAccountId()).thenReturn("000000000000");
            stubFreshStart("cid-new", 6440);

            Cluster cluster = cluster();
            cluster.setAccountId("999999999999");
            manager.startCluster(cluster);

            assertEquals("floci-eks-999999999999.demo", cluster.getDockerName());
            verify(lifecycleManager).removeIfExists("floci-eks-999999999999.demo");
        }
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

            EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            ecr = Mockito.mock(EmulatorConfig.EcrServiceConfig.class);
            when(config.services()).thenReturn(Mockito.mock(EmulatorConfig.ServicesConfig.class));
            when(config.services().eks()).thenReturn(eks);
            when(config.services().ecr()).thenReturn(ecr);
            when(config.port()).thenReturn(4566);
            when(config.defaultRegion()).thenReturn("us-east-1");
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(eks.ecrRegistryMirror()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());
            when(ecr.enabled()).thenReturn(true);

            DockerHostResolver dockerHostResolver = Mockito.mock(DockerHostResolver.class);
            when(dockerHostResolver.resolve()).thenReturn("floci");
            manager = new EksClusterManager(
                    Mockito.mock(ContainerBuilder.class), lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    dockerHostResolver, registryManager, config,
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

    @Nested
    class ConfigureLinkLocalMetadataEndpoint {

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private EmulatorConfig.Ec2ServiceConfig ec2;
        private ContainerLifecycleManager lifecycleManager;
        private DockerClient dockerClient;
        private Ec2MetadataServer metadataServer;
        private DockerHostResolver dockerHostResolver;
        private RegionResolver regionResolver;
        private EksClusterManager manager;
        private ExecCreateCmd execCreate;
        private List<String[]> capturedCmds;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            ec2 = Mockito.mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(services.eks()).thenReturn(eks);
            when(services.ec2()).thenReturn(ec2);
            when(ec2.imdsPort()).thenReturn(9169);
            when(eks.imds()).thenReturn(true);

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            dockerClient = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

            metadataServer = Mockito.mock(Ec2MetadataServer.class);
            dockerHostResolver = Mockito.mock(DockerHostResolver.class);
            when(dockerHostResolver.resolve()).thenReturn("floci-host");

            regionResolver = Mockito.mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

            capturedCmds = new ArrayList<>();
            execCreate = Mockito.mock(ExecCreateCmd.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF));
            ExecCreateCmdResponse execResponse = Mockito.mock(ExecCreateCmdResponse.class);
            when(execResponse.getId()).thenReturn("exec-123");
            when(dockerClient.execCreateCmd(anyString())).thenReturn(execCreate);
            when(execCreate.withCmd(any(String[].class))).thenAnswer(inv -> {
                Object[] args = inv.getArguments();
                if (args.length == 1 && args[0] instanceof String[] command) {
                    capturedCmds.add(command);
                } else {
                    capturedCmds.add(Arrays.copyOf(args, args.length, String[].class));
                }
                return execCreate;
            });
            when(execCreate.exec()).thenReturn(execResponse);

            ExecStartCmd execStart = Mockito.mock(ExecStartCmd.class);
            when(dockerClient.execStartCmd(anyString())).thenReturn(execStart);
            when(execStart.exec(any())).thenAnswer(inv -> {
                ResultCallback<Frame> cb = inv.getArgument(0);
                cb.onComplete();
                return cb;
            });

            InspectExecCmd inspectExec = Mockito.mock(InspectExecCmd.class);
            InspectExecResponse inspectResponse = Mockito.mock(InspectExecResponse.class);
            when(inspectResponse.getExitCodeLong()).thenReturn(0L);
            when(inspectExec.exec()).thenReturn(inspectResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            InspectContainerCmd inspectContainer = Mockito.mock(InspectContainerCmd.class);
            InspectContainerResponse containerResponse = Mockito.mock(InspectContainerResponse.class);
            NetworkSettings netSettings = Mockito.mock(NetworkSettings.class);
            when(inspectContainer.exec()).thenReturn(containerResponse);
            when(dockerClient.inspectContainerCmd(anyString())).thenReturn(inspectContainer);
            when(containerResponse.getNetworkSettings()).thenReturn(netSettings);
            when(netSettings.getIpAddress()).thenReturn("172.17.0.2");

            manager = new EksClusterManager(
                    Mockito.mock(ContainerBuilder.class), lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    dockerHostResolver, Mockito.mock(EcrRegistryManager.class),
                    config, regionResolver, metadataServer);
        }

        @Test
        void synthesizesClusterNodeInstance() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setRoleArn("arn:aws:iam::123456789012:role/eks-node-role");

            Instance instance = manager.synthesizeClusterNodeInstance(cluster, "172.17.0.2", "us-west-2", "123456789012");
            assertNotNull(instance);
            assertTrue(instance.getInstanceId().startsWith("i-"));
            assertTrue(instance.getInstanceId().length() >= 19);
            assertEquals("ami-eks-k3s", instance.getImageId());
            assertEquals("m5.large", instance.getInstanceType());
            assertEquals("us-west-2a", instance.getPlacement().getAvailabilityZone());
            assertEquals("us-west-2", instance.getRegion());
            assertEquals("172.17.0.2", instance.getPrivateIpAddress());
            assertEquals("ip-172-17-0-2.us-west-2.compute.internal", instance.getPrivateDnsName());
            assertEquals("arn:aws:iam::123456789012:instance-profile/prod-cluster-node-profile", instance.getIamInstanceProfileArn());
            assertNotEquals(cluster.getRoleArn(), instance.getIamInstanceProfileArn());
            assertEquals("running", instance.getState().getName());
        }

        @Test
        void sameNameClustersInDifferentRegionsGetDistinctInstanceIds() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");

            Instance inst1 = manager.synthesizeClusterNodeInstance(cluster, "172.17.0.2", "us-east-1", "123456789012");
            Instance inst2 = manager.synthesizeClusterNodeInstance(cluster, "172.17.0.2", "us-west-2", "123456789012");

            assertNotNull(inst1);
            assertNotNull(inst2);
            assertNotEquals(inst1.getInstanceId(), inst2.getInstanceId());
        }

        @Test
        void configuresMetadataProxyWhenEnabled() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            verify(metadataServer).reconcileContainerAddresses(any(), any());
            assertEquals(2, capturedCmds.size());
            // First command: install probe
            assertTrue(capturedCmds.get(0)[2].contains("command -v socat"));
            // Second command: start command with 169.254.169.254
            assertTrue(capturedCmds.get(1)[2].contains("169.254.169.254"));
            assertTrue(capturedCmds.get(1)[2].contains("TCP:floci-host:9169"));

            assertNotNull(manager.getRegisteredClusterNodeInstance(cluster));
        }

        @Test
        void skipsWhenImdsIsDisabled() {
            when(eks.imds()).thenReturn(false);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            verifyNoInteractions(metadataServer);
            verifyNoInteractions(dockerClient);
            assertNull(manager.getRegisteredClusterNodeInstance(cluster));
        }

        @Test
        void failureToWireLogsAndDoesNotAbort() {
            when(dockerClient.execCreateCmd(anyString())).thenThrow(new RuntimeException("docker exec failed"));

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            // Failure to wire proxy should log warning and continue without throwing
            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");
        }

        @Test
        void unregisterMetadataEndpointRemovesInstance() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");
            assertNotNull(manager.getRegisteredClusterNodeInstance(cluster));

            manager.unregisterMetadataEndpoint(cluster);
            verify(metadataServer).unregisterInstance(any());
            assertNull(manager.getRegisteredClusterNodeInstance(cluster));
        }
    }
}
