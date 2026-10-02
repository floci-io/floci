package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallManagerTest {

    @Test
    void helperCarriesTheResolverAndHostsItsWorkloadsUse() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("floci/network-helper:local");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info linux = mock(Info.class);
        when(linux.getOsType()).thenReturn("linux");
        when(dockerClient.infoCmd().exec()).thenReturn(linux);
        when(dockerClient.inspectImageCmd(anyString()).exec()).thenReturn(null);
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
                "us-east-1", Optional.empty(), Map.of()));

        ArgumentCaptor<ContainerSpec> helper = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(helper.capture());
        assertTrue(helper.getValue().dnsServers().contains("172.18.0.2"), "the helper uses Floci's DNS");
        assertTrue(helper.getValue().extraHosts().contains("host.docker.internal:host-gateway"),
                "the helper resolves host.docker.internal");
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
}
