package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
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
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
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
    void webhookPathHandlesMissingArnOrCreatedAtGracefully() {
        Cluster cluster = new Cluster();
        cluster.setName("demo");
        cluster.setAccountId("123456789012");
        assertEquals("/_floci/eks/clusters/demo/token-webhook/scope/123456789012"
                + "/us-east-1/1970-01-01T00:00:00Z", EksClusterManager.webhookPath(cluster));
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
    void registriesYamlTlsAliasesPreserveLoopbackMirrorsAndInternalHttpEndpoint() {
        String endpoint = "http://host.docker.internal:4577";
        String yaml = EksClusterManager.buildRegistriesYaml(
                "111122223333", AwsRegions.ALL, 4577, endpoint, true);

        for (String region : AwsRegions.ALL) {
            assertTrue(yaml.contains("\"111122223333.dkr.ecr." + region + ".localhost:4577\":"));
            assertTrue(yaml.contains("\"111122223333.dkr.ecr." + region + ".localhost.floci.io:4577\":"));
        }
        assertTrue(yaml.contains("\"localhost:4577\":"));
        assertTrue(yaml.contains("\"localhost.floci.io:4577\":"));
        assertFalse(yaml.contains("*"), "mirrors must not intercept unrelated registries");
        assertFalse(yaml.contains("amazonaws.com"));
        assertFalse(yaml.contains("docker.io"));
        assertFalse(yaml.contains("ghcr.io"));
        assertFalse(yaml.contains("https://"), "internal pulls still use the HTTP data plane");
        long endpoints = yaml.lines().filter(line -> line.contains("- \"" + endpoint + "\"")).count();
        assertEquals(2L * (AwsRegions.ALL.size() + 1), endpoints);
    }

    @Test
    void registriesYamlKeepsTheExistingOutputWhenTlsUrisAreDisabled() {
        String yaml = EksClusterManager.buildRegistriesYaml(
                "111122223333", List.of("eu-central-1"), 4566, "http://floci:4566", false);

        assertEquals("""
                mirrors:
                  "111122223333.dkr.ecr.eu-central-1.localhost:4566":
                    endpoint:
                      - "http://floci:4566"
                  "localhost:4566":
                    endpoint:
                      - "http://floci:4566"
                """, yaml);
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
        when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startClusterEmbeddedDnsFlag(boolean embeddedDns) {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.services().eks().defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
        when(config.services().eks().embeddedDns()).thenReturn(embeddedDns);

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.create(any())).thenReturn("container-id");

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        EksClusterManager manager = new EksClusterManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                config, new RegionResolver("us-east-1", "000000000000"));

        Cluster cluster = new Cluster();
        cluster.setName("my-cluster");

        manager.startCluster(cluster);

        if (embeddedDns) {
            verify(builder).withEmbeddedDns();
        } else {
            verify(builder, never()).withEmbeddedDns();
        }
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
            verify(lifecycleManager).removeVolume("floci-aws-eks-demo");
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

            assertEquals("floci-aws-eks-demo", manager.clusterResourceName(cluster));
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

            assertEquals("floci-aws-eks-999999999999.demo", cluster.getDockerName());
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

            assertEquals("floci-aws-eks-999999999999.demo", cluster.getDockerName());
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

            assertEquals("floci-aws-eks-999999999999.demo", cluster.getDockerName());
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

            assertEquals("floci-aws-eks-999999999999.demo", cluster.getDockerName());
            verify(lifecycleManager).removeIfExists("floci-aws-eks-999999999999.demo");
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
        private EmulatorConfig config;
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

            config = Mockito.mock(EmulatorConfig.class);
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
        void injectsTheMirrorIntoTheContainer() throws Exception {
            manager.injectEcrRegistryMirror("container-1", "demo");

            verify(registryManager).ensureStarted();
            verify(copyCmd).withRemotePath("/etc");
            verify(copyCmd).exec();
            verify(config, never()).tls();
            String yaml = Files.readString(tempDir.resolve("registries/demo/registries.yaml"));
            assertFalse(yaml.contains("localhost.floci.io"));
        }

        @Test
        void injectsTlsAliasesWhenTlsAndTlsUrisAreEnabled() throws Exception {
            EmulatorConfig.TlsConfig tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
            when(config.tls()).thenReturn(tls);
            when(tls.enabled()).thenReturn(true);
            when(ecr.tlsUri()).thenReturn(true);

            manager.injectEcrRegistryMirror("container-1", "demo");

            String yaml = Files.readString(tempDir.resolve("registries/demo/registries.yaml"));
            assertTrue(yaml.contains("\"000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566\":"));
            assertTrue(yaml.contains("\"localhost.floci.io:4566\":"));
            assertTrue(yaml.contains("- \"http://floci:4566\""));
            assertFalse(yaml.contains("https://"));
            verify(copyCmd).exec();
        }

        @Test
        void omitsTlsAliasesWhenTlsIsDisabled() throws Exception {
            EmulatorConfig.TlsConfig tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
            when(config.tls()).thenReturn(tls);
            when(ecr.tlsUri()).thenReturn(true);

            manager.injectEcrRegistryMirror("container-1", "demo");

            String yaml = Files.readString(tempDir.resolve("registries/demo/registries.yaml"));
            assertFalse(yaml.contains("localhost.floci.io"));
            assertTrue(yaml.contains("\"localhost:4566\":"));
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
            when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                    "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

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
        void derivesClusterNodeProviderIdMatchingExpectedFormat() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");

            String providerId = manager.deriveClusterNodeProviderId(cluster, "us-west-2", "123456789012");
            assertEquals("aws:///us-west-2a/i-0e413a1bfb5c3cd79", providerId);
        }

        @Test
        void derivedProviderIdAgreesWithSynthesizedClusterNodeInstance() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setRoleArn("arn:aws:iam::123456789012:role/eks-node-role");

            Instance instance = manager.synthesizeClusterNodeInstance(cluster, "172.17.0.2", "us-west-2", "123456789012");
            String providerId = manager.deriveClusterNodeProviderId(cluster, "us-west-2", "123456789012");

            String expectedProviderId = "aws:///" + instance.getPlacement().getAvailabilityZone() + "/" + instance.getInstanceId();
            assertEquals(expectedProviderId, providerId);
            assertEquals(instance.getInstanceId(), manager.deriveClusterNodeInstanceId(cluster, "us-west-2", "123456789012"));
            assertEquals(instance.getPlacement().getAvailabilityZone(), manager.deriveClusterNodeAvailabilityZone(cluster, "us-west-2"));
        }

        @Test
        void deriveClusterNodeAvailabilityZoneDerivesFromClusterArn() {
            Cluster cluster = new Cluster();
            cluster.setArn("arn:aws:eks:ap-southeast-1:123456789012:cluster/test-cluster");
            assertEquals("ap-southeast-1a", manager.deriveClusterNodeAvailabilityZone(cluster));
        }

        @Test
        void configuresMetadataProxyWhenEnabled() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            verify(metadataServer).reconcileContainerAddresses(any(), any());
            // imds=true, imdsPodNetwork=false (default): 2 commands (install probe, start proxy)
            assertEquals(2, capturedCmds.size());
            // First command: install probe
            assertTrue(capturedCmds.get(0)[2].contains("command -v socat"));
            // Second command: start command with 169.254.169.254
            assertTrue(capturedCmds.get(1)[2].contains("169.254.169.254"));
            assertTrue(capturedCmds.get(1)[2].contains("TCP:floci-host:9169"));

            assertNotNull(manager.getRegisteredClusterNodeInstance(cluster));
        }

        @Test
        void configuresPodNetworkRoutingWhenImdsPodNetworkEnabled() {
            when(eks.imdsPodNetwork()).thenReturn(true);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            verify(metadataServer).reconcileContainerAddresses(any(), any());
            assertEquals(3, capturedCmds.size());
            // First command: install probe
            assertTrue(capturedCmds.get(0)[2].contains("command -v socat"));
            // Second command: start command with 169.254.169.254
            assertTrue(capturedCmds.get(1)[2].contains("169.254.169.254"));
            assertTrue(capturedCmds.get(1)[2].contains("TCP:floci-host:9169"));
            // Third command: pod network routing with iptables
            assertTrue(capturedCmds.get(2)[2].contains("FLOCI-LINK-LOCAL"));
            assertTrue(capturedCmds.get(2)[2].contains("10.42.0.0/16"));
            assertTrue(capturedCmds.get(2)[2].contains("169.254.169.254"));
            assertFalse(capturedCmds.get(2)[2].contains("169.254.170.23"),
                    "IMDS pod routing must not route Pod Identity traffic");

            assertNotNull(manager.getRegisteredClusterNodeInstance(cluster));
        }

        @Test
        void skipsWhenImdsIsDisabled() {
            when(eks.imds()).thenReturn(false);
            when(eks.imdsPodNetwork()).thenReturn(true);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            verifyNoInteractions(metadataServer);
            verifyNoInteractions(dockerClient);
            assertNull(manager.getRegisteredClusterNodeInstance(cluster));
        }

        @Test
        void registerClusterNodeInstanceRegistersWithoutImds() {
            Cluster cluster = new Cluster();
            cluster.setName("no-imds-cluster");
            ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
            vpcConfig.setVpcId("vpc-12345678");
            vpcConfig.setSubnetIds(List.of("subnet-87654321"));
            cluster.setResourcesVpcConfig(vpcConfig);

            manager.registerClusterNodeInstance(cluster, "container-no-imds");
            Instance instance = manager.getRegisteredClusterNodeInstance(cluster);
            assertNotNull(instance);
            assertEquals("vpc-12345678", instance.getVpcId());
            assertEquals("subnet-87654321", instance.getSubnetId());
            assertNotNull(instance.getLaunchTime());
            assertTrue(instance.getTags().stream().anyMatch(t -> "Name".equals(t.getKey()) && "no-imds-cluster-node".equals(t.getValue())));
            assertTrue(instance.getTags().stream().anyMatch(t -> "kubernetes.io/cluster/no-imds-cluster".equals(t.getKey()) && "owned".equals(t.getValue())));
            assertTrue(instance.getTags().stream().anyMatch(t -> "eks:cluster-name".equals(t.getKey()) && "no-imds-cluster".equals(t.getValue())));
        }

        @Test
        void clusterNodeInstanceProviderFindsAndListsRegisteredInstances() {
            Cluster cluster = new Cluster();
            cluster.setName("prov-cluster");
            cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/prov-cluster");

            manager.registerClusterNodeInstance(cluster, "container-prov");
            Instance registered = manager.getRegisteredClusterNodeInstance(cluster);
            assertNotNull(registered);

            assertTrue(manager.findInstance("123456789012", "us-east-1", registered.getInstanceId()).isPresent());
            assertEquals(registered.getInstanceId(), manager.findInstance("123456789012", "us-east-1", registered.getInstanceId()).get().getInstanceId());
            assertTrue(manager.findInstance(null, null, registered.getInstanceId()).isEmpty());
            assertTrue(manager.findInstance("123456789012", null, registered.getInstanceId()).isPresent());
            assertTrue(manager.findInstance("999999999999", "us-east-1", registered.getInstanceId()).isEmpty());
            assertTrue(manager.findInstance("123456789012", "eu-central-1", registered.getInstanceId()).isEmpty());

            List<Instance> eastInstances = manager.listInstances("123456789012", "us-east-1");
            assertTrue(eastInstances.stream().anyMatch(i -> registered.getInstanceId().equals(i.getInstanceId())));
            assertTrue(manager.listInstances(null, "us-east-1").isEmpty());
            assertTrue(manager.listInstances("999999999999", "us-east-1").isEmpty());
            assertTrue(manager.listInstances("123456789012", "eu-central-1").isEmpty());

            manager.unregisterMetadataEndpoint(cluster);
            assertTrue(manager.findInstance("123456789012", "us-east-1", registered.getInstanceId()).isEmpty());
            assertTrue(manager.listInstances("123456789012", "us-east-1").isEmpty());
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
        void podNetworkRoutingFailureLogsWarningAndDoesNotAbort() {
            when(eks.imdsPodNetwork()).thenReturn(true);

            InspectExecCmd inspectExec = Mockito.mock(InspectExecCmd.class);
            InspectExecResponse inspectResponse = Mockito.mock(InspectExecResponse.class);
            // Simulate install and start succeeding (0), but routing failing (1)
            when(inspectResponse.getExitCodeLong()).thenReturn(0L, 0L, 1L);
            when(inspectExec.exec()).thenReturn(inspectResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            // Pod network routing failure logs warning and continues without throwing
            manager.configureLinkLocalMetadataEndpoint(cluster, "container-42");

            assertEquals(3, capturedCmds.size());
            assertNotNull(manager.getRegisteredClusterNodeInstance(cluster));
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

    @Nested
    class ConfigurePodIdentityRelay {

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private EmulatorConfig.TlsConfig tls;
        private ContainerLifecycleManager lifecycleManager;
        private DockerClient dockerClient;
        private DockerHostResolver dockerHostResolver;
        private EksClusterManager manager;
        private List<String[]> capturedCmds;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
            when(config.services()).thenReturn(services);
            when(services.eks()).thenReturn(eks);
            when(eks.podIdentityWebhook()).thenReturn(true);
            when(config.tls()).thenReturn(tls);
            when(tls.enabled()).thenReturn(true);
            when(config.port()).thenReturn(4566);

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            dockerClient = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

            dockerHostResolver = Mockito.mock(DockerHostResolver.class);
            when(dockerHostResolver.resolve()).thenReturn("floci-host");

            capturedCmds = new ArrayList<>();
            ExecCreateCmd execCreate = Mockito.mock(ExecCreateCmd.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF));
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

            manager = new EksClusterManager(
                    Mockito.mock(ContainerBuilder.class), lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    dockerHostResolver, Mockito.mock(EcrRegistryManager.class),
                    config, Mockito.mock(RegionResolver.class), null);
        }

        @Test
        void configuresRelayWhenPodIdentityEnabled() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configurePodIdentityRelay(cluster, "cid-1");

            assertEquals(3, capturedCmds.size());
            assertTrue(capturedCmds.get(0)[2].contains("command -v socat"));
            assertTrue(capturedCmds.get(1)[2].contains("169.254.170.23"));
            assertTrue(capturedCmds.get(1)[2].contains("TCP:floci-host:4566"));
            assertTrue(capturedCmds.get(1)[2].contains("floci-pod-identity-proxy.pid"));
            assertTrue(capturedCmds.get(2)[2].contains("FLOCI-LINK-LOCAL"));
            assertTrue(capturedCmds.get(2)[2].contains("169.254.170.23"));
            assertFalse(capturedCmds.get(2)[2].contains("169.254.169.254"),
                    "Pod identity relay must not route IMDS traffic");
        }

        @Test
        void skipsRelayWhenPodIdentityDisabled() {
            when(eks.podIdentityWebhook()).thenReturn(false);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configurePodIdentityRelay(cluster, "cid-1");

            assertTrue(capturedCmds.isEmpty());
        }

        @Test
        void skipsRelayWhenTlsDisabled() {
            when(tls.enabled()).thenReturn(false);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            manager.configurePodIdentityRelay(cluster, "cid-1");

            assertTrue(capturedCmds.isEmpty());
        }

        @Test
        void failsGracefullyWhenExecFails() {
            InspectExecCmd inspectExec = Mockito.mock(InspectExecCmd.class);
            InspectExecResponse inspectResponse = Mockito.mock(InspectExecResponse.class);
            when(inspectResponse.getExitCodeLong()).thenReturn(1L);
            when(inspectExec.exec()).thenReturn(inspectResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");

            assertDoesNotThrow(() -> manager.configurePodIdentityRelay(cluster, "cid-1"));
        }
    }

    @Nested
    class IrsaSigningKey {

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private ContainerLifecycleManager lifecycleManager;
        private ContainerBuilder.Builder builder;
        private DockerClient dockerClient;
        private CopyArchiveToContainerCmd copyCmd;
        private EksOidcService oidcService;
        private RegionResolver regionResolver;
        private EksClusterManager manager;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.eks()).thenReturn(eks);
            when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
            when(eks.apiServerBasePort()).thenReturn(6440);
            when(eks.apiServerMaxPort()).thenReturn(6499);
            when(eks.dockerNetwork()).thenReturn(Optional.empty());
            when(eks.disableCni()).thenReturn(false);
            when(eks.iamAuthWebhook()).thenReturn(false);
            when(eks.ecrRegistryMirror()).thenReturn(false);
            when(eks.imds()).thenReturn(false);
            when(eks.endpointMode()).thenReturn("host");
            when(config.defaultAccountId()).thenReturn("000000000000");

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            dockerClient = Mockito.mock(DockerClient.class);
            copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
            when(lifecycleManager.create(any())).thenReturn("container-id");
            when(lifecycleManager.startCreated(any(), any())).thenReturn(
                    new ContainerInfo("container-id", Map.of()));

            ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
            builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

            regionResolver = Mockito.mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
            when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                    "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

            oidcService = Mockito.mock(EksOidcService.class);
            when(oidcService.newIssuerUrl(anyString())).thenReturn(
                    "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER12345678901234567890");

            manager = new EksClusterManager(containerBuilder, lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                    config, regionResolver, null, oidcService);
        }

        @Test
        void buildIrsaServerArgsProducesCorrectFlagsAndAudiences() {
            String issuer = "https://oidc.eks.us-west-2.amazonaws.com/id/MYISSUER";
            List<String> args = EksClusterManager.buildIrsaServerArgs(issuer);

            assertTrue(args.contains("--kube-apiserver-arg=service-account-signing-key-file=/etc/sa-signing-key.pem"));
            assertTrue(args.contains("--kube-apiserver-arg=service-account-key-file=/etc/sa-public-key.pem"));
            assertTrue(args.contains("--kube-apiserver-arg=service-account-issuer=" + issuer));
            assertTrue(args.contains("--kube-apiserver-arg=service-account-issuer=https://kubernetes.default.svc.cluster.local"));
            assertTrue(args.contains("--kube-apiserver-arg=api-audiences=https://kubernetes.default.svc.cluster.local,sts.amazonaws.com"));

            assertThrows(IllegalArgumentException.class, () -> EksClusterManager.buildIrsaServerArgs(null));
            assertThrows(IllegalArgumentException.class, () -> EksClusterManager.buildIrsaServerArgs("  "));
        }

        @Test
        void startClusterInjectsKeysAndConfiguresArgsWhenEnabled(@TempDir Path tempDir) {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER";
            ClusterOidcKey key = new ClusterOidcKey(issuer, "kid-1", "pubKeyBase64", "privKeyBase64");
            when(oidcService.ensureKeyForAccount(anyString(), anyString(), anyString())).thenReturn(key);
            when(oidcService.exportSigningKeyPem(key)).thenReturn("-----BEGIN PRIVATE KEY-----\npriv\n-----END PRIVATE KEY-----\n");
            when(oidcService.exportPublicKeyPem(key)).thenReturn("-----BEGIN PUBLIC KEY-----\npub\n-----END PUBLIC KEY-----\n");

            Cluster cluster = new Cluster();
            cluster.setName("my-cluster");
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertTrue(cmd.contains("--kube-apiserver-arg=service-account-signing-key-file=/etc/sa-signing-key.pem"));
            assertTrue(cmd.contains("--kube-apiserver-arg=service-account-key-file=/etc/sa-public-key.pem"));
            assertTrue(cmd.contains("--kube-apiserver-arg=service-account-issuer=" + issuer));
            assertTrue(cmd.contains("--kube-apiserver-arg=service-account-issuer=https://kubernetes.default.svc.cluster.local"));
            assertTrue(cmd.contains("--kube-apiserver-arg=api-audiences=https://kubernetes.default.svc.cluster.local,sts.amazonaws.com"));

            Path keysDir = manager.resolveKeysDir(cluster);
            Path privFile = keysDir.resolve(EksClusterManager.SA_SIGNING_KEY_FILE);
            Path pubFile = keysDir.resolve(EksClusterManager.SA_PUBLIC_KEY_FILE);
            assertEquals(tempDir.resolve("keys").resolve("000000000000").resolve("us-east-1").resolve("my-cluster"), keysDir);
            assertTrue(Files.exists(privFile));
            assertTrue(Files.exists(pubFile));

            verify(copyCmd).withHostResource(privFile.toString());
            verify(copyCmd).withHostResource(pubFile.toString());
            verify(copyCmd, atLeastOnce()).withRemotePath("/etc");
            verify(copyCmd, atLeastOnce()).exec();
        }

        @Test
        void startClusterOmitsIrsaArgsAndKeyInjectionWhenDisabled(@TempDir Path tempDir) {
            when(eks.irsaSigningKey()).thenReturn(false);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            Cluster cluster = new Cluster();
            cluster.setName("my-cluster");

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertFalse(cmd.stream().anyMatch(a -> a.contains("service-account-signing-key-file")));
            assertFalse(cmd.stream().anyMatch(a -> a.contains("service-account-key-file")));
            assertFalse(cmd.stream().anyMatch(a -> a.contains("service-account-issuer")));
            assertFalse(cmd.stream().anyMatch(a -> a.contains("api-audiences")));

            verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
            assertFalse(Files.exists(tempDir.resolve("keys")));
        }

        @Test
        void startClusterContinuesWhenSigningKeyInjectionFails(@TempDir Path tempDir) {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER";
            ClusterOidcKey key = new ClusterOidcKey(issuer, "kid-1", "pubKeyBase64", "privKeyBase64");
            when(oidcService.ensureKeyForAccount(anyString(), anyString(), anyString())).thenReturn(key);
            when(oidcService.exportSigningKeyPem(key)).thenReturn("-----BEGIN PRIVATE KEY-----\npriv\n-----END PRIVATE KEY-----\n");
            when(oidcService.exportPublicKeyPem(key)).thenReturn("-----BEGIN PUBLIC KEY-----\npub\n-----END PUBLIC KEY-----\n");

            when(copyCmd.exec()).thenThrow(new RuntimeException("Docker copy failed"));

            Cluster cluster = new Cluster();
            cluster.setName("my-cluster");

            // Must not throw despite injection failure
            manager.startCluster(cluster);
            assertEquals("container-id", cluster.getContainerId());
        }

        @Test
        void startClusterContinuesWhenWritingKeysFails(@TempDir Path tempDir) throws Exception {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            Cluster cluster = new Cluster();
            cluster.setName("my-cluster");

            // Create a regular file where the keys directory would be, causing createDirectories to fail
            Path blocker = manager.resolveKeysDir(cluster);
            Files.createDirectories(blocker.getParent());
            Files.writeString(blocker, "blocker");

            String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER";
            ClusterOidcKey key = new ClusterOidcKey(issuer, "kid-1", "pubKeyBase64", "privKeyBase64");
            when(oidcService.ensureKeyForAccount(anyString(), anyString(), anyString())).thenReturn(key);

            // Must not throw despite file write failure
            manager.startCluster(cluster);
            assertEquals("container-id", cluster.getContainerId());
        }

        @Test
        void restoreClusterReinjectsSigningKeysForSurvivingContainer(@TempDir Path tempDir) {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER";
            ClusterOidcKey key = new ClusterOidcKey(issuer, "kid-1", "pubKeyBase64", "privKeyBase64");
            when(oidcService.ensureKeyForAccount(anyString(), anyString(), anyString())).thenReturn(key);
            when(oidcService.exportSigningKeyPem(key)).thenReturn("-----BEGIN PRIVATE KEY-----\npriv\n-----END PRIVATE KEY-----\n");
            when(oidcService.exportPublicKeyPem(key)).thenReturn("-----BEGIN PUBLIC KEY-----\npub\n-----END PUBLIC KEY-----\n");

            Container container = Mockito.mock(Container.class);
            when(container.getId()).thenReturn("surviving-container-id");
            when(lifecycleManager.findByName("floci-eks-my-cluster")).thenReturn(Optional.of(container));
            when(lifecycleManager.adopt(anyString(), any())).thenReturn(
                    new ContainerInfo("surviving-container-id", Map.of(6443, new ContainerLifecycleManager.EndpointInfo("localhost", 6500)), Map.of(6443, 6500)));

            Cluster cluster = new Cluster();
            cluster.setName("my-cluster");
            cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));

            manager.restoreCluster(cluster);

            Path keysDir = manager.resolveKeysDir(cluster);
            Path privFile = keysDir.resolve(EksClusterManager.SA_SIGNING_KEY_FILE);
            Path pubFile = keysDir.resolve(EksClusterManager.SA_PUBLIC_KEY_FILE);
            assertTrue(Files.exists(privFile));
            assertTrue(Files.exists(pubFile));

            verify(copyCmd).withHostResource(privFile.toString());
            verify(copyCmd).withHostResource(pubFile.toString());
        }

        @Test
        void startClusterConfiguresKubeletProviderIdArg() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setAccountId("123456789012");
            cluster.setArn("arn:aws:eks:us-west-2:123456789012:cluster/prod-cluster");

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            String expectedProviderId = manager.deriveClusterNodeProviderId(cluster);
            assertEquals("aws:///us-west-2a/i-0e413a1bfb5c3cd79", expectedProviderId);
            assertTrue(cmd.contains("--kubelet-arg=provider-id=" + expectedProviderId));
        }

        @Test
        void startClusterContinuesWhenProviderIdDerivationFails() {
            EksClusterManager spyManager = Mockito.spy(manager);
            Mockito.doThrow(new RuntimeException("derivation failure"))
                    .when(spyManager).deriveClusterNodeProviderId(any());

            Cluster cluster = new Cluster();
            cluster.setName("fail-cluster");

            assertDoesNotThrow(() -> spyManager.startCluster(cluster));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("--kubelet-arg=provider-id=")));
        }

        @Test
        void startClusterConfiguresKubeletTopologyLabelsArg() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setAccountId("123456789012");
            cluster.setArn("arn:aws:eks:us-west-2:123456789012:cluster/prod-cluster");

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            String expectedAz = manager.deriveClusterNodeAvailabilityZone(cluster, "us-west-2");
            assertEquals("us-west-2a", expectedAz);
            assertTrue(cmd.contains("--kubelet-arg=node-labels=topology.kubernetes.io/zone=" + expectedAz
                    + ",topology.kubernetes.io/region=us-west-2"));
        }

        @Test
        void topologyLabelZoneMatchesProviderIdZone() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setAccountId("123456789012");
            cluster.setArn("arn:aws:eks:eu-central-1:123456789012:cluster/prod-cluster");

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            String providerIdArg = cmd.stream()
                    .filter(arg -> arg.startsWith("--kubelet-arg=provider-id="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("provider-id arg missing"));
            String providerId = providerIdArg.substring("--kubelet-arg=provider-id=".length());
            String[] parts = providerId.split("/");
            assertTrue(parts.length >= 4, "Provider ID should have format aws:///<zone>/<instance-id>");
            String providerZone = parts[3];

            String nodeLabelsArg = cmd.stream()
                    .filter(arg -> arg.startsWith("--kubelet-arg=node-labels="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("node-labels arg missing"));
            String labelZone = null;
            String labelsPart = nodeLabelsArg.substring("--kubelet-arg=node-labels=".length());
            for (String pair : labelsPart.split(",")) {
                String[] kv = pair.split("=", 2);
                if ("topology.kubernetes.io/zone".equals(kv[0])) {
                    labelZone = kv[1];
                    break;
                }
            }
            assertNotNull(labelZone, "topology.kubernetes.io/zone must be present in node-labels arg");
            assertEquals(providerZone, labelZone);
        }

        @Test
        void startClusterContinuesWhenTopologyLabelsDerivationFails() {
            EksClusterManager spyManager = Mockito.spy(manager);
            Mockito.doThrow(new RuntimeException("derivation failure"))
                    .when(spyManager).deriveClusterNodeAvailabilityZone(any(), any());

            Cluster cluster = new Cluster();
            cluster.setName("fail-cluster");

            assertDoesNotThrow(() -> spyManager.startCluster(cluster));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("--kubelet-arg=node-labels=")));
        }

        @Test
        void signingKeyFilesWrittenWithRestrictivePermissions(@TempDir Path tempDir) throws Exception {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/TESTISSUER";
            ClusterOidcKey key = new ClusterOidcKey(issuer, "kid-1", "pubKeyBase64", "privKeyBase64");
            when(oidcService.ensureKeyForAccount(anyString(), anyString(), anyString())).thenReturn(key);
            when(oidcService.exportSigningKeyPem(key)).thenReturn("-----BEGIN PRIVATE KEY-----\npriv\n-----END PRIVATE KEY-----\n");
            when(oidcService.exportPublicKeyPem(key)).thenReturn("-----BEGIN PUBLIC KEY-----\npub\n-----END PUBLIC KEY-----\n");

            Cluster cluster = new Cluster();
            cluster.setName("perm-cluster");

            manager.startCluster(cluster);

            Path keysDir = manager.resolveKeysDir(cluster);
            Path privFile = keysDir.resolve(EksClusterManager.SA_SIGNING_KEY_FILE);
            Path pubFile = keysDir.resolve(EksClusterManager.SA_PUBLIC_KEY_FILE);

            try {
                Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(keysDir);
                assertEquals(PosixFilePermissions.fromString("rwx------"), dirPerms);

                Set<PosixFilePermission> privPerms = Files.getPosixFilePermissions(privFile);
                assertEquals(PosixFilePermissions.fromString("rw-------"), privPerms);

                Set<PosixFilePermission> pubPerms = Files.getPosixFilePermissions(pubFile);
                assertEquals(PosixFilePermissions.fromString("rw-------"), pubPerms);
            } catch (UnsupportedOperationException ignored) {
                // Ignore on non-POSIX platforms
            }
        }

        @Test
        void twoAccountsWithSameClusterNameHaveIsolatedSigningKeyFiles(@TempDir Path tempDir) throws Exception {
            when(eks.irsaSigningKey()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());

            String clusterName = "shared-cluster";

            Cluster cluster1 = new Cluster();
            cluster1.setName(clusterName);
            cluster1.setAccountId("111122223333");
            cluster1.setArn("arn:aws:eks:us-east-1:111122223333:cluster/" + clusterName);
            String issuer1 = "https://oidc.eks.us-east-1.amazonaws.com/id/ISSUER1111";
            cluster1.setIdentity(new ClusterIdentity(new OidcIdentity(issuer1)));
            ClusterOidcKey key1 = new ClusterOidcKey(issuer1, "kid-1", "pub1", "priv1");

            Cluster cluster2 = new Cluster();
            cluster2.setName(clusterName);
            cluster2.setAccountId("444455556666");
            cluster2.setArn("arn:aws:eks:us-east-1:444455556666:cluster/" + clusterName);
            String issuer2 = "https://oidc.eks.us-east-1.amazonaws.com/id/ISSUER4444";
            cluster2.setIdentity(new ClusterIdentity(new OidcIdentity(issuer2)));
            ClusterOidcKey key2 = new ClusterOidcKey(issuer2, "kid-2", "pub2", "priv2");

            Cluster cluster3 = new Cluster();
            cluster3.setName(clusterName);
            cluster3.setAccountId("111122223333");
            cluster3.setArn("arn:aws:eks:eu-west-1:111122223333:cluster/" + clusterName);
            String issuer3 = "https://oidc.eks.eu-west-1.amazonaws.com/id/ISSUER3333";
            cluster3.setIdentity(new ClusterIdentity(new OidcIdentity(issuer3)));
            ClusterOidcKey key3 = new ClusterOidcKey(issuer3, "kid-3", "pub3", "priv3");

            when(oidcService.exportSigningKeyPem(key1)).thenReturn("priv-key-1");
            when(oidcService.exportPublicKeyPem(key1)).thenReturn("pub-key-1");
            when(oidcService.exportSigningKeyPem(key2)).thenReturn("priv-key-2");
            when(oidcService.exportPublicKeyPem(key2)).thenReturn("pub-key-2");
            when(oidcService.exportSigningKeyPem(key3)).thenReturn("priv-key-3");
            when(oidcService.exportPublicKeyPem(key3)).thenReturn("pub-key-3");

            EksClusterManager.SigningKeyFiles files1 = manager.writeSigningKeyFiles(cluster1, key1);
            EksClusterManager.SigningKeyFiles files2 = manager.writeSigningKeyFiles(cluster2, key2);
            EksClusterManager.SigningKeyFiles files3 = manager.writeSigningKeyFiles(cluster3, key3);

            assertNotNull(files1);
            assertNotNull(files2);
            assertNotNull(files3);
            assertNotEquals(files1.signingKeyPath(), files2.signingKeyPath());
            assertNotEquals(files1.signingKeyPath(), files3.signingKeyPath());
            assertNotEquals(files2.signingKeyPath(), files3.signingKeyPath());

            Path expectedDir1 = tempDir.resolve("keys").resolve("111122223333").resolve("us-east-1").resolve(clusterName);
            Path expectedDir2 = tempDir.resolve("keys").resolve("444455556666").resolve("us-east-1").resolve(clusterName);
            Path expectedDir3 = tempDir.resolve("keys").resolve("111122223333").resolve("eu-west-1").resolve(clusterName);
            assertEquals(expectedDir1, manager.resolveKeysDir(cluster1));
            assertEquals(expectedDir2, manager.resolveKeysDir(cluster2));
            assertEquals(expectedDir3, manager.resolveKeysDir(cluster3));

            assertEquals("priv-key-1", Files.readString(files1.signingKeyPath()));
            assertEquals("pub-key-1", Files.readString(files1.publicKeyPath()));
            assertEquals("priv-key-2", Files.readString(files2.signingKeyPath()));
            assertEquals("pub-key-2", Files.readString(files2.publicKeyPath()));
            assertEquals("priv-key-3", Files.readString(files3.signingKeyPath()));
            assertEquals("pub-key-3", Files.readString(files3.publicKeyPath()));
        }
    }

    @Nested
    class NativeClusterRuntime {

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private ContainerLifecycleManager lifecycleManager;
        private ContainerBuilder containerBuilder;
        private ContainerBuilder.Builder builder;
        private PortAllocator portAllocator;
        private EksClusterManager manager;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.eks()).thenReturn(eks);
            when(eks.defaultImage()).thenReturn("rancher/k3s:latest");
            when(eks.imageTemplate()).thenReturn(Optional.empty());
            when(eks.apiServerBasePort()).thenReturn(6500);
            when(eks.apiServerMaxPort()).thenReturn(6599);
            when(eks.dockerNetwork()).thenReturn(Optional.empty());
            when(eks.disableCni()).thenReturn(false);
            when(eks.iamAuthWebhook()).thenReturn(false);
            when(eks.ecrRegistryMirror()).thenReturn(false);
            when(eks.imds()).thenReturn(false);
            when(eks.endpointMode()).thenReturn("host");
            when(config.defaultAccountId()).thenReturn("000000000000");

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            when(lifecycleManager.create(any())).thenReturn("container-id");
            when(lifecycleManager.startCreated(any(), any())).thenReturn(
                    new ContainerInfo("container-id", Map.of()));

            containerBuilder = Mockito.mock(ContainerBuilder.class);
            builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

            portAllocator = Mockito.mock(PortAllocator.class);
            when(portAllocator.allocate(6500, 6599)).thenReturn(6500);

            RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
            when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                    "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

            manager = new EksClusterManager(containerBuilder, lifecycleManager,
                    Mockito.mock(ContainerDetector.class), portAllocator,
                    Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                    config, regionResolver, null, Mockito.mock(EksOidcService.class));
        }

        @Test
        void resolveClusterImageMapsSupportedVersions() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");
            cluster.setExplicitVersion(true);

            cluster.setVersion("1.28");
            assertEquals("rancher/k3s:v1.28.15-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.29");
            assertEquals("rancher/k3s:v1.29.14-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.30");
            assertEquals("rancher/k3s:v1.30.10-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.31");
            assertEquals("rancher/k3s:v1.31.5-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.32");
            assertEquals("rancher/k3s:v1.32.2-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.33");
            assertEquals("rancher/k3s:v1.33.1-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.34");
            assertEquals("rancher/k3s:v1.34.1-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.35");
            assertEquals("rancher/k3s:v1.35.0-k3s1", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.36");
            assertEquals("rancher/k3s:v1.36.0-k3s1", manager.resolveClusterImage(cluster));
        }

        @Test
        void resolveClusterImageDynamicallyFormatsUnmappedVersions() {
            Cluster cluster = new Cluster();
            cluster.setName("future-cluster");
            cluster.setExplicitVersion(true);
            cluster.setVersion("1.37");

            assertEquals("rancher/k3s:v1.37.0-k3s1", manager.resolveClusterImage(cluster));
        }

        @Test
        void resolveClusterImageFallsBackToDefaultWhenVersionNullOrDefaultWithoutExplicit() {
            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");
            cluster.setVersion(null);
            assertEquals("rancher/k3s:latest", manager.resolveClusterImage(cluster));

            cluster.setVersion("1.29");
            cluster.setExplicitVersion(false);
            assertEquals("rancher/k3s:latest", manager.resolveClusterImage(cluster));

            cluster.setExplicitVersion(true);
            assertEquals("rancher/k3s:v1.29.14-k3s1", manager.resolveClusterImage(cluster));
        }

        @Test
        void resolveClusterImagePreservesExplicitVersionAcrossSerializationRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            // Cluster with explicit version 1.29 (matching default version string)
            Cluster explicit129 = new Cluster();
            explicit129.setName("explicit-129");
            explicit129.setVersion("1.29");
            explicit129.setExplicitVersion(true);

            String jsonExplicit = mapper.writeValueAsString(explicit129);
            assertTrue(jsonExplicit.contains("\"explicitVersion\":true"));

            Cluster reloadedExplicit = mapper.readValue(jsonExplicit, Cluster.class);
            assertTrue(reloadedExplicit.isExplicitVersion());
            assertEquals("rancher/k3s:v1.29.14-k3s1", manager.resolveClusterImage(reloadedExplicit));

            // Cluster without explicit version
            Cluster unversioned = new Cluster();
            unversioned.setName("unversioned");
            unversioned.setVersion("1.29");
            unversioned.setExplicitVersion(false);

            String jsonUnversioned = mapper.writeValueAsString(unversioned);
            assertFalse(jsonUnversioned.contains("explicitVersion"));

            Cluster reloadedUnversioned = mapper.readValue(jsonUnversioned, Cluster.class);
            assertFalse(reloadedUnversioned.isExplicitVersion());
            assertEquals("rancher/k3s:latest", manager.resolveClusterImage(reloadedUnversioned));
        }

        @Test
        void resolveClusterImageUsesConfiguredImageTemplate() {
            when(eks.imageTemplate()).thenReturn(Optional.of("internal.registry.io/k3s:v%s-custom"));

            Cluster cluster = new Cluster();
            cluster.setName("test-cluster");
            cluster.setVersion("1.31");

            assertEquals("internal.registry.io/k3s:v1.31-custom", manager.resolveClusterImage(cluster));
        }

        @Test
        void defaultPodCidrConstantIsK3sStandard() {
            assertEquals("10.42.0.0/16", EksClusterManager.DEFAULT_POD_CIDR);
        }

        @Test
        void buildServerArgsPropagatesServiceAndPodCidr() {
            List<String> args = EksClusterManager.buildServerArgs(false, "172.20.0.0/16", "10.44.0.0/16");
            assertTrue(args.contains("--service-cidr=172.20.0.0/16"));
            assertTrue(args.contains("--cluster-cidr=10.44.0.0/16"));
            assertFalse(args.contains("--flannel-backend=none"));
        }

        @Test
        void buildServerArgsWithDisableCniAndCidrs() {
            List<String> args = EksClusterManager.buildServerArgs(true, "10.100.0.0/16", "10.42.0.0/16");
            assertTrue(args.contains("--flannel-backend=none"));
            assertTrue(args.contains("--disable-network-policy"));
            assertTrue(args.contains("--disable-kube-proxy"));
            assertTrue(args.contains("--service-cidr=10.100.0.0/16"));
            assertTrue(args.contains("--cluster-cidr=10.42.0.0/16"));
        }

        @Test
        void startClusterCleansUpContainerOnStartFailure() {
            when(lifecycleManager.startCreated(any(), any()))
                    .thenThrow(new RuntimeException("Container failed to boot"));

            Cluster cluster = new Cluster();
            cluster.setName("fail-cluster");
            cluster.setVersion("1.30");

            assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));
            verify(lifecycleManager, Mockito.times(2)).removeIfExists("floci-aws-eks-fail-cluster");
        }
    }

    @Nested
    class RegisterPodIdentityWebhook {

        @TempDir
        Path tempDir;

        private static final String CA_PEM = "-----BEGIN CERTIFICATE-----\nfloci-root-ca\n-----END CERTIFICATE-----\n";

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private EmulatorConfig.TlsConfig tls;
        private ContainerLifecycleManager lifecycleManager;
        private CopyArchiveToContainerCmd copyCmd;
        private EksClusterManager manager;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
            when(config.services()).thenReturn(services);
            when(services.eks()).thenReturn(eks);
            when(config.tls()).thenReturn(tls);
            when(config.port()).thenReturn(4566);
            when(eks.podIdentityWebhook()).thenReturn(true);
            when(eks.dataPath()).thenReturn(tempDir.toString());
            when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
            when(eks.dockerNetwork()).thenReturn(Optional.empty());
            when(eks.endpointMode()).thenReturn("host");
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(tls.enabled()).thenReturn(true);

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
            when(lifecycleManager.create(any())).thenReturn("container-1");
            when(lifecycleManager.startCreated(any(), any()))
                    .thenReturn(new ContainerInfo("container-1", Map.of()));

            ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

            DockerHostResolver dockerHostResolver = Mockito.mock(DockerHostResolver.class);
            when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

            RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
            when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                    "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

            FlociCertificateAuthority certificateAuthority = Mockito.mock(FlociCertificateAuthority.class);
            when(certificateAuthority.caPem()).thenReturn(CA_PEM);

            manager = new EksClusterManager(
                    containerBuilder, lifecycleManager,
                    Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                    dockerHostResolver, Mockito.mock(EcrRegistryManager.class), config,
                    regionResolver, null, null, certificateAuthority);
        }

        private Cluster cluster() {
            Cluster cluster = new Cluster();
            cluster.setName("demo");
            cluster.setAccountId("000000000000");
            return cluster;
        }

        @Test
        void configurationCarriesTheCaBundleAndAnHttpsUrl() {
            String manifest = EksClusterManager.buildPodIdentityWebhookConfiguration(
                    "https://host.docker.internal:4566/_floci/eks/clusters/demo/pod-identity-webhook/scope/000000000000", CA_PEM);

            assertTrue(manifest.contains("kind: MutatingWebhookConfiguration"));
            assertTrue(manifest.contains("url: \"https://host.docker.internal:4566"
                    + "/_floci/eks/clusters/demo/pod-identity-webhook/scope/000000000000\""));
            assertTrue(manifest.contains("caBundle: \""
                    + Base64.getEncoder().encodeToString(CA_PEM.getBytes(StandardCharsets.UTF_8)) + "\""));
            assertTrue(manifest.contains("failurePolicy: Ignore"));
            assertTrue(manifest.contains("timeoutSeconds: 3"));
            assertTrue(manifest.contains("operations: [\"CREATE\"]"));
            assertTrue(manifest.contains("resources: [\"pods\"]"));
            assertFalse(manifest.contains("url: \"http://"), "Kubernetes rejects a non-https webhook URL");
        }

        @Test
        void writesTheManifestIntoTheK3sManifestsDirectory() throws Exception {
            manager.registerPodIdentityWebhook("container-1", cluster());

            verify(copyCmd).withRemotePath("/var/lib/rancher/k3s");
            verify(copyCmd).exec();
            String written = Files.readString(tempDir.resolve("webhook").resolve("demo")
                    .resolve("floci-eks-pod-identity.yaml"));
            assertTrue(written.contains("https://host.docker.internal:4566"
                    + "/_floci/eks/clusters/demo/pod-identity-webhook/scope/000000000000"));
        }

        @Test
        void skipsWithAWarningWhenTlsIsDisabled() {
            when(tls.enabled()).thenReturn(false);

            manager.registerPodIdentityWebhook("container-1", cluster());

            verify(lifecycleManager, never()).getDockerClient();
        }

        @Test
        void skipsWhenTheKnobIsOff() {
            when(eks.podIdentityWebhook()).thenReturn(false);

            manager.registerPodIdentityWebhook("container-1", cluster());

            verify(lifecycleManager, never()).getDockerClient();
        }

        @Test
        void registrationFailureLeavesTheClusterRunning() {
            when(copyCmd.exec()).thenThrow(new RuntimeException("no such container"));
            Cluster cluster = cluster();

            manager.startCluster(cluster);

            verify(copyCmd).exec();
            verify(lifecycleManager).startCreated(any(), any());
            assertEquals("container-1", cluster.getContainerId());
        }
    }

    @Nested
    class ControlPlaneLogs {

        private EmulatorConfig config;
        private EmulatorConfig.EksServiceConfig eks;
        private ContainerLifecycleManager lifecycleManager;
        private ContainerLogStreamer logStreamer;
        private EksClusterManager manager;
        private Closeable mockHandle;
        private ContainerBuilder.Builder builder;

        @BeforeEach
        void setUp() {
            config = Mockito.mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
            eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.eks()).thenReturn(eks);
            when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
            when(eks.imageTemplate()).thenReturn(Optional.empty());
            when(eks.apiServerBasePort()).thenReturn(6440);
            when(eks.apiServerMaxPort()).thenReturn(6499);
            when(eks.dockerNetwork()).thenReturn(Optional.empty());
            when(eks.disableCni()).thenReturn(false);
            when(eks.iamAuthWebhook()).thenReturn(false);
            when(eks.ecrRegistryMirror()).thenReturn(false);
            when(eks.imds()).thenReturn(false);
            when(eks.endpointMode()).thenReturn("host");
            when(eks.keepRunningOnShutdown()).thenReturn(false);
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(config.defaultRegion()).thenReturn("us-east-1");

            EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
            when(config.storage()).thenReturn(storage);
            when(storage.mode()).thenReturn("memory");
            when(storage.pruneVolumesOnDelete()).thenReturn(false);

            lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            when(lifecycleManager.create(any())).thenReturn("container-id-123456789012345678901234567890");
            when(lifecycleManager.startCreated(any(), any())).thenReturn(
                    new ContainerInfo("container-id-123456789012345678901234567890", Map.of()));

            ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
            builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

            PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
            when(portAllocator.allocate(6440, 6499)).thenReturn(6443);

            RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
            when(regionResolver.buildGlobalArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                    "arn:aws:" + invocation.getArgument(0) + "::" + invocation.getArgument(1) + ":" + invocation.getArgument(2));

            logStreamer = Mockito.mock(ContainerLogStreamer.class);
            mockHandle = Mockito.mock(Closeable.class);
            when(logStreamer.attachForAccount(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockHandle);
            when(logStreamer.attachFromNowForAccount(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockHandle);

            manager = new EksClusterManager(containerBuilder, lifecycleManager,
                    Mockito.mock(ContainerDetector.class), portAllocator,
                    Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                    config, regionResolver, null, null, logStreamer);
        }

        @Test
        void clusterWithEnabledTypesCreatesLogGroupAndAttachesStream() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.startCluster(cluster);

            verify(logStreamer).attachForAccount(
                    eq("000000000000"),
                    eq("container-id-123456789012345678901234567890"),
                    eq("/aws/eks/prod-cluster/cluster"),
                    eq("kube-apiserver-container-id-1234567890123456789"),
                    eq("us-east-1"),
                    eq("eks:prod-cluster")
            );
            assertEquals(mockHandle, manager.getLogHandle(cluster));
        }

        @Test
        void clusterWithNoEnabledTypesDoesNeither() {
            Cluster cluster = new Cluster();
            cluster.setName("no-logs-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), false))));

            manager.startCluster(cluster);

            verifyNoInteractions(logStreamer);
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void clusterWithNonApiEnabledTypeDoesNeither() {
            Cluster cluster = new Cluster();
            cluster.setName("scheduler-only-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("scheduler"), true))));

            manager.startCluster(cluster);

            verifyNoInteractions(logStreamer);
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void clusterWithNullLoggingDoesNeither() {
            Cluster cluster = new Cluster();
            cluster.setName("null-logs-cluster");
            cluster.setLogging(null);

            manager.startCluster(cluster);

            verifyNoInteractions(logStreamer);
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void attachmentFailureLogsWarningAndContinuesWithoutAborting() {
            when(logStreamer.attachForAccount(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Docker attach connection failed"));

            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api", "audit"), true))));

            manager.startCluster(cluster);

            assertNull(manager.getLogHandle(cluster));
            assertEquals("container-id-123456789012345678901234567890", cluster.getContainerId());
        }

        @Test
        void logHandleIsReleasedOnClusterStop() {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.startCluster(cluster);
            assertEquals(mockHandle, manager.getLogHandle(cluster));

            manager.stopCluster(cluster);

            verify(lifecycleManager).stopAndRemove("container-id-123456789012345678901234567890", mockHandle);
            assertNull(manager.getLogHandle(cluster));
        }

        /**
         * Explicit DeleteCluster tears down through {@code stopCluster}. The shutdown retention
         * option is decided by {@link EksService#shutdown()}, so it must not leave a deleted
         * cluster's container running.
         */
        @Test
        void stopClusterRemovesTheContainerEvenWhenShutdownRetentionIsEnabled() {
            when(eks.keepRunningOnShutdown()).thenReturn(true);

            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.startCluster(cluster);
            manager.stopCluster(cluster);

            verify(lifecycleManager).stopAndRemove("container-id-123456789012345678901234567890", mockHandle);
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void detachClusterClosesTheLogStreamAndLeavesTheContainerRunning() throws Exception {
            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.startCluster(cluster);
            manager.detachCluster(cluster);

            verify(mockHandle).close();
            verify(lifecycleManager, never()).stopAndRemove(anyString(), any());
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void restoreClusterAttachesLogsForAdoptedContainer() {
            Container container = Mockito.mock(Container.class);
            when(container.getId()).thenReturn("adopted-container-id-12345678901234567890");
            when(lifecycleManager.findByName("floci-eks-prod-cluster")).thenReturn(Optional.of(container));
            when(lifecycleManager.adopt(anyString(), any())).thenReturn(
                    new ContainerInfo("adopted-container-id-12345678901234567890",
                            Map.of(6443, new ContainerLifecycleManager.EndpointInfo("localhost", 6500)),
                            Map.of(6443, 6500)));

            Cluster cluster = new Cluster();
            cluster.setName("prod-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.restoreCluster(cluster);

            verify(logStreamer).attachFromNowForAccount(
                    eq("000000000000"),
                    eq("adopted-container-id-12345678901234567890"),
                    eq("/aws/eks/prod-cluster/cluster"),
                    eq("kube-apiserver-adopted-container-id-12345678901"),
                    eq("us-east-1"),
                    eq("eks:prod-cluster")
            );
            assertEquals(mockHandle, manager.getLogHandle(cluster));
        }

        @Test
        void hasLoggingEnabledVerification() {
            assertFalse(EksClusterManager.hasLoggingEnabled(null));

            Cluster cluster = new Cluster();
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of()));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), false))));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of(new LogSetup(List.of(), true))));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of(new LogSetup(null, true))));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of(
                    new LogSetup(List.of("api"), false),
                    new LogSetup(List.of("scheduler"), true)
            )));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster));
            assertTrue(EksClusterManager.hasLoggingEnabled(cluster, "scheduler"));

            cluster.setLogging(new Logging(List.of(
                    new LogSetup(List.of("api"), true),
                    new LogSetup(List.of("scheduler"), false)
            )));
            assertTrue(EksClusterManager.hasLoggingEnabled(cluster));

            cluster.setLogging(new Logging(List.of(
                    new LogSetup(List.of("audit"), true)
            )));
            assertTrue(EksClusterManager.hasLoggingEnabled(cluster));
            assertTrue(EksClusterManager.hasLoggingEnabled(cluster, "audit"));
            assertFalse(EksClusterManager.hasLoggingEnabled(cluster, "api"));
        }

        @Test
        void clusterWithAuditLoggingAddsAuditArgsAndInjectsPolicyFile(@TempDir Path tempDir) {
            when(eks.dataPath()).thenReturn(tempDir.toString());
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            CopyArchiveToContainerCmd copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);

            Cluster cluster = new Cluster();
            cluster.setName("audit-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertTrue(cmd.contains("--kube-apiserver-arg=audit-policy-file=/etc/audit-policy.yaml"));
            assertTrue(cmd.contains("--kube-apiserver-arg=audit-log-path=/var/log/audit.log"));
            assertTrue(cmd.contains("--kube-apiserver-arg=audit-log-maxage=30"));
            assertTrue(cmd.contains("--kube-apiserver-arg=audit-log-maxbackup=10"));
            assertTrue(cmd.contains("--kube-apiserver-arg=audit-log-maxsize=100"));

            verify(dockerClient).copyArchiveToContainerCmd("container-id-123456789012345678901234567890");
            verify(copyCmd).withRemotePath("/etc");
        }

        @Test
        void clusterWithoutAuditLoggingDoesNotAddAuditArgsOrInjectPolicyFile(@TempDir Path tempDir) {
            when(eks.dataPath()).thenReturn(tempDir.toString());
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

            Cluster cluster = new Cluster();
            cluster.setName("no-audit-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

            manager.startCluster(cluster);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(builder).withCmd(cmdCaptor.capture());
            List<String> cmd = cmdCaptor.getValue();

            assertFalse(cmd.stream().anyMatch(arg -> arg.contains("audit-policy-file")));
            assertFalse(cmd.stream().anyMatch(arg -> arg.contains("audit-log-path")));

            verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
        }

        @Test
        void copyAuditPolicyFailureLogsWarningAndContinuesStartup(@TempDir Path tempDir) {
            when(eks.dataPath()).thenReturn(tempDir.toString());
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            CopyArchiveToContainerCmd copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
            doThrow(new RuntimeException("Docker copy failed")).when(copyCmd).exec();

            Cluster cluster = new Cluster();
            cluster.setName("audit-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));

            assertDoesNotThrow(() -> manager.startCluster(cluster));
            assertEquals("container-id-123456789012345678901234567890", cluster.getContainerId());
        }

        @Test
        void auditFollowerHandleIsReleasedOnClusterStop() throws Exception {
            DockerClient dockerClient = Mockito.mock(DockerClient.class);
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
            ExecCreateCmd execCreateCmd = Mockito.mock(ExecCreateCmd.class, Mockito.RETURNS_SELF);
            ExecCreateCmdResponse execCreate = Mockito.mock(ExecCreateCmdResponse.class);
            when(execCreate.getId()).thenReturn("exec-123");
            when(dockerClient.execCreateCmd(anyString())).thenReturn(execCreateCmd);
            when(execCreateCmd.exec()).thenReturn(execCreate);

            ExecStartCmd execStartCmd = Mockito.mock(ExecStartCmd.class, Mockito.RETURNS_SELF);
            when(dockerClient.execStartCmd("exec-123")).thenReturn(execStartCmd);
            ResultCallback.Adapter<?> mockAuditHandle = Mockito.mock(ResultCallback.Adapter.class);
            Mockito.doReturn(mockAuditHandle).when(execStartCmd).exec(any());

            Cluster cluster = new Cluster();
            cluster.setName("audit-cluster");
            cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));

            manager.startCluster(cluster);
            assertEquals(mockAuditHandle, manager.getLogHandle(cluster));

            manager.stopCluster(cluster);
            verify(lifecycleManager).stopAndRemove("container-id-123456789012345678901234567890", mockAuditHandle);
            assertNull(manager.getLogHandle(cluster));
        }

        @Test
        void auditPolicyMatchesExpectedAwsEksRules() {
            String policy = EksClusterManager.buildAuditPolicy();
            assertNotNull(policy);
            assertTrue(policy.contains("apiVersion: audit.k8s.io/v1"));
            assertTrue(policy.contains("kind: Policy"));
            assertTrue(policy.contains("resourceNames: [\"aws-auth\"]"));
            assertTrue(policy.contains("users: [\"system:kube-proxy\"]"));
            assertTrue(policy.contains("userGroups: [\"system:nodes\"]"));
            assertTrue(policy.contains("users: [\"kubelet\"]"));
            assertTrue(policy.contains("resources: [\"secrets\", \"configmaps\"]"));
            assertTrue(policy.contains("resources: [\"tokenreviews\"]"));
            assertTrue(policy.contains("resources: [\"events\"]"));
            assertTrue(policy.contains("nonResourceURLs:"));
            assertTrue(policy.contains("/healthz*"));
            assertTrue(policy.contains("/version"));
        }
    }
}
