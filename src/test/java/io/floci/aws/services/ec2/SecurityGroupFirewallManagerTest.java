package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallManagerTest {

    @Test
    void helperCarriesTheResolverAndHostsItsWorkloadsUse() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(linux.getArchitecture()).thenReturn("amd64");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn("linux");
        when(image.getArch()).thenReturn("amd64");
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(image);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("stop after the spec"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        assertThrows(IllegalStateException.class, () -> manager.createNamespace("ec2", "i-1", "000000000000",
                "us-east-1", Optional.empty(), Map.of(8080, 0), Map.of(), "127.0.0.1"));

        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertEquals(Map.of(8080, 0), helper.getValue().portBindings());
        assertEquals(Map.of(8080, "127.0.0.1"), helper.getValue().portBindingHostIps());
        assertTrue(helper.getValue().dnsServers().contains("172.18.0.2"), "the helper uses Floci's DNS");
        assertTrue(helper.getValue().extraHosts().contains("host.docker.internal:host-gateway"),
                "the helper resolves host.docker.internal");
    }

    @Test
    void aProtectedHelperTakesTheLinkLocalAddressItsWorkloadsShareAndIsRemovedByName() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(linux.getArchitecture()).thenReturn("amd64");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn("linux");
        when(image.getArch()).thenReturn("amd64");
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(image);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("stop after the spec"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        assertThrows(IllegalStateException.class, () -> manager.createNamespace("ecs", "abc123", "000000000000",
                "us-east-1", Optional.of("floci-net"), Map.of(), Map.of(), "127.0.0.1", List.of("169.254.170.1")));

        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertEquals(List.of("169.254.170.1"), helper.getValue().linkLocalIps());
        assertEquals("floci-net", helper.getValue().networkMode());
        assertEquals("floci-aws-sg-abc123", helper.getValue().name());

        manager.removeProtectedNamespaceStrict("abc123");
        verify(lifecycleManager).removeIfExistsStrict("floci-aws-sg-abc123");
    }

    @Test
    void aSharedHelperNeedsNeitherEnforcementNorRootfulDockerAndInstallsNoFirewall() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(false);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info rootless = mock(Info.class);
        when(rootless.getOsType()).thenReturn("linux");
        when(rootless.getArchitecture()).thenReturn("amd64");
        when(rootless.getSecurityOptions()).thenReturn(List.of("name=seccomp,profile=builtin", "name=rootless"));
        when(dockerClient.infoCmd().exec()).thenReturn(rootless);
        when(dockerClient.inspectContainerCmd("helper-id").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of("floci-net", new ContainerNetwork().withIpv4Address("172.18.0.9")));
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("helper-id", Map.of()));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        SecurityGroupFirewallManager.Namespace namespace = manager.createSharedNamespace("ecs", "abc123",
                "000000000000", "us-east-1", Optional.of("floci-net"), Map.of(80, 0),
                Map.of("io.floci.ecs.run", "run-1"), "127.0.0.1", List.of("169.254.170.1"));

        assertEquals("helper-id", namespace.helperId());
        assertEquals("172.18.0.9", namespace.transportAddress());
        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertEquals("floci-aws-netns-abc123", helper.getValue().name());
        assertEquals("floci-net", helper.getValue().networkMode());
        assertEquals(List.of("169.254.170.1"), helper.getValue().linkLocalIps());
        assertEquals(Map.of(80, 0), helper.getValue().portBindings());
        assertEquals(Map.of(80, "127.0.0.1"), helper.getValue().portBindingHostIps());
        assertTrue(helper.getValue().dnsServers().contains("172.18.0.2"), "the helper uses Floci's DNS");
        assertEquals("ecs", helper.getValue().labels().get("io.floci.service"));
        assertEquals("4566", helper.getValue().labels().get("io.floci.owner"));
        assertEquals("run-1", helper.getValue().labels().get("io.floci.ecs.run"));
        // Not a protected helper: a startup with enforcement on must not quarantine it as one.
        assertFalse(helper.getValue().labels().containsKey("io.floci.security-group.helper"));
        verify(dockerClient, never()).execCreateCmd(anyString());

        manager.removeSharedNamespaceStrict("abc123");
        verify(lifecycleManager).removeIfExistsStrict("floci-aws-netns-abc123");
    }

    @Test
    void onlyFlocisDnsIsExemptFromTheSecurityGroups() {
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().containerFallbackServers()).thenReturn(List.of("8.8.8.8", "8.8.4.4"));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(mock(DockerClient.class),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config, embeddedDnsServer);

        assertEquals(List.of("172.18.0.2"), manager.vpcResolvers());

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        assertEquals(List.of(), manager.vpcResolvers());
    }
    @Test
    void survivingEcsHelpersAreFoundUnderTheNewAndLegacyLabelsAndRemovedOnlyWhenTheirOwnersAgree() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());
        ListContainersCmd byNewKeys = mock(ListContainersCmd.class);
        ListContainersCmd byLegacyKeys = mock(ListContainersCmd.class);
        when(listCmd.withLabelFilter(Map.of("io.floci.security-group.helper", "true"))).thenReturn(byNewKeys);
        when(listCmd.withLabelFilter(Map.of("floci.security-group-helper", "true"))).thenReturn(byLegacyKeys);
        Container newOnly = exitedEcsHelper("new-only",
                Map.of("io.floci.security-group.helper", "true", "io.floci.owner", "4566"));
        Container legacyOnly = exitedEcsHelper("legacy-only",
                Map.of("floci.security-group-helper", "true", "floci_owner_port", "4566"));
        Container both = exitedEcsHelper("both", Map.of(
                "io.floci.security-group.helper", "true", "io.floci.owner", "4566",
                "floci.security-group-helper", "true", "floci_owner_port", "4566"));
        Container disagreeing = exitedEcsHelper("disagreeing", Map.of(
                "io.floci.security-group.helper", "true", "io.floci.owner", "4566",
                "floci.security-group-helper", "true", "floci_owner_port", "4567"));
        when(byNewKeys.exec()).thenReturn(List.of(newOnly, both, disagreeing));
        when(byLegacyKeys.exec()).thenReturn(List.of(legacyOnly, both, disagreeing));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), lifecycleManager, config, mock(EmbeddedDnsServer.class));

        manager.quarantineSurvivingNamespaces();

        verify(lifecycleManager).removeIfExists("new-only");
        verify(lifecycleManager).removeIfExists("legacy-only");
        verify(lifecycleManager).removeIfExists("both");
        verify(lifecycleManager, never()).removeIfExists("disagreeing");
    }

    // Catches: the helper's ruleset upload calling copyArchive directly, so a connection dropped
    // mid-upload failed the namespace instead of being retried with a fresh tar stream.
    @Test
    void aRulesetUploadThatLosesItsConnectionIsRetriedWithTheWholeArchive() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(linux.getArchitecture()).thenReturn("amd64");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn("linux");
        when(image.getArch()).thenReturn("amd64");
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(image);
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.5");
        when(dockerClient.inspectContainerCmd("helper-1").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of("floci-net", network));

        List<byte[]> uploads = new CopyOnWriteArrayList<>();
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("helper-1")).thenReturn(copy);
        doAnswer(invocation -> {
            uploads.add(invocation.<InputStream>getArgument(0).readAllBytes());
            return copy;
        }).when(copy).withTarInputStream(any());
        doThrow(new RuntimeException(new IOException("Broken pipe"))).doNothing().when(copy).exec();

        ExecStartCmd execStart = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd(any())).thenReturn(execStart);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.inspectExecCmd(any()).exec().getExitCodeLong()).thenReturn(0L);

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("helper-1", Map.of(), Map.of()));
        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(dockerClient,
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer), lifecycleManager, config,
                embeddedDnsServer);

        SecurityGroupFirewallManager.Namespace namespace = manager.createNamespace("ecs", "abc123",
                "000000000000", "us-east-1", Optional.of("floci-net"), Map.of(), Map.of(), "127.0.0.1", List.of());

        assertEquals("172.18.0.5", namespace.transportAddress());
        verify(copy, times(2)).exec();
        verify(copy, times(2)).withRemotePath("/tmp");
        assertEquals(2, uploads.size());
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.get(1)))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("floci-sg.nft", entry.getName());
            assertEquals(SecurityGroupNftCompiler.initialRuleset(),
                    new String(tar.readAllBytes(), StandardCharsets.UTF_8),
                    "the retry should upload the whole ruleset, not a drained stream");
        }
        verify(lifecycleManager, never()).removeIfExists("helper-1");
    }

    private static Container exitedEcsHelper(String id, Map<String, String> ownerLabels) {
        Map<String, String> labels = new HashMap<>(ownerLabels);
        labels.put("io.floci.service", "ecs");
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getState()).thenReturn("exited");
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
