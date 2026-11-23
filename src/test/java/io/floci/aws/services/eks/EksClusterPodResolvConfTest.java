package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.eks.model.Cluster;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolver file a cluster's k3s node hands its pods: Floci's embedded DNS server alone, so CoreDNS
 * forwards to Floci rather than to the public resolver k3s picks when the node's own resolv.conf names
 * only Docker's loopback resolver.
 */
class EksClusterPodResolvConfTest {

    private static final String FLOCI_DNS = "172.18.0.2";
    private static final String RESOLV_CONF_ARG = "--resolv-conf=" + EksClusterManager.POD_RESOLV_CONF_PATH;

    private EmulatorConfig config;
    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private CopyArchiveToContainerCmd copyCmd;
    private EksClusterManager manager;

    /** Each file copied into the container, by its path there, and the ones copied after it started. */
    private final Map<String, String> copiedFiles = new LinkedHashMap<>();
    private final Set<String> copiedAfterStart = new HashSet<>();
    private String remotePath;
    private String copiedEntry;
    private boolean started;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.services().eks().defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
        when(config.services().eks().embeddedDns()).thenReturn(true);

        containerBuilder = Mockito.mock(ContainerBuilder.class);
        builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        when(containerBuilder.embeddedDnsServerIpReachableOn(any())).thenReturn(Optional.of(FLOCI_DNS));

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        when(lifecycleManager.startCreated(any(), any())).thenAnswer(invocation -> {
            started = true;
            return new ContainerInfo("container-id", Map.of());
        });
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenAnswer(invocation -> {
            remotePath = null;
            copiedEntry = null;
            return copyCmd;
        });
        when(copyCmd.withRemotePath(anyString())).thenAnswer(invocation -> {
            remotePath = invocation.getArgument(0);
            return copyCmd;
        });
        when(copyCmd.withTarInputStream(any())).thenAnswer(invocation -> {
            recordFiles(invocation.getArgument(0));
            return copyCmd;
        });

        manager = EksClusterManagers.create(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerDetector.class), Mockito.mock(PortAllocator.class),
                Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                config, new RegionResolver("us-east-1", "000000000000"), null, null, null, null, null, 0L);
    }

    @Test
    void podsGetAResolverFileNamingOnlyFlocisDnsServer() {
        manager.startCluster(cluster(Map.of()));

        assertTrue(serverArgs().contains(RESOLV_CONF_ARG), serverArgs().toString());
        assertEquals("nameserver " + FLOCI_DNS + "\n", copiedFiles.get(EksClusterManager.POD_RESOLV_CONF_PATH));
        assertFalse(copiedAfterStart.contains(EksClusterManager.POD_RESOLV_CONF_PATH),
                "k3s reads the file as it starts, so it must be there first");
    }

    @Test
    void podsKeepK3sResolverWhenEmbeddedDnsIsNotRunning() {
        when(containerBuilder.embeddedDnsServerIpReachableOn(any())).thenReturn(Optional.empty());

        manager.startCluster(cluster(Map.of()));

        assertNoResolverFile();
    }

    @Test
    void podsKeepK3sResolverWhenTheClustersNetworkCannotReachFlocisDns() {
        // A pod given only a resolver it cannot reach would lose the public one k3s picks as well.
        when(config.services().eks().dockerNetwork()).thenReturn(Optional.of("cluster-net"));
        when(containerBuilder.embeddedDnsServerIpReachableOn(Optional.of("cluster-net"))).thenReturn(Optional.empty());

        manager.startCluster(cluster(Map.of()));

        assertNoResolverFile();
    }

    @Test
    void podsKeepK3sResolverWhenEmbeddedDnsIsOff() {
        when(config.services().eks().embeddedDns()).thenReturn(false);

        manager.startCluster(cluster(Map.of()));

        verify(builder, never()).withEmbeddedDns();
        assertNoResolverFile();
    }

    @Test
    void aClusterThatSetsTheKubeletsResolverFileKeepsItsOwn() {
        manager.startCluster(cluster(Map.of("floci:kubelet-arg:resolv-conf", "/etc/own-resolv.conf")));

        assertTrue(serverArgs().contains("--kubelet-arg=resolv-conf=/etc/own-resolv.conf"), serverArgs().toString());
        assertNoResolverFile();
    }

    @Test
    void aResolverFileThatCannotBeCopiedFailsTheStartAndRemovesTheContainer() {
        when(copyCmd.exec()).thenAnswer(invocation -> {
            if (EksClusterManager.POD_RESOLV_CONF_TAR_ENTRY.equals(copiedEntry)) {
                throw new IllegalStateException("copy refused");
            }
            return null;
        });
        Cluster cluster = cluster(Map.of());

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).startCreated(any(), any());
        verify(lifecycleManager).removeIfExistsStrict(cluster.getDockerName(), true);
    }

    private void assertNoResolverFile() {
        assertFalse(serverArgs().stream().anyMatch(arg -> arg.startsWith("--resolv-conf")), serverArgs().toString());
        assertFalse(copiedFiles.containsKey(EksClusterManager.POD_RESOLV_CONF_PATH), copiedFiles.keySet().toString());
    }

    private List<String> serverArgs() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(builder).withCmd(cmd.capture());
        return cmd.getValue();
    }

    private void recordFiles(InputStream archive) throws Exception {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                copiedEntry = entry.getName();
                if (entry.isFile()) {
                    String path = remotePath + "/" + entry.getName();
                    copiedFiles.put(path, new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                    if (started) {
                        copiedAfterStart.add(path);
                    }
                }
            }
        }
    }

    private static Cluster cluster(Map<String, String> tags) {
        Cluster cluster = new Cluster();
        cluster.setName("my-cluster");
        cluster.setTags(tags);
        return cluster;
    }
}
