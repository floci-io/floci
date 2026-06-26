package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerBuilderTest {

    @Test
    void withDockerNetwork_usesExplicitServiceNetworkFirst() {
        TestFixture fixture = new TestFixture();
        when(fixture.currentContainerNetworkResolver.resolveNetworkName()).thenReturn(Optional.of("compose_default"));

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withDockerNetwork(Optional.of("lambda_network"))
                .build();

        assertEquals("lambda_network", spec.networkMode());
    }

    @Test
    void withDockerNetwork_usesGlobalNetworkBeforeDetectedCurrentNetwork() {
        TestFixture fixture = new TestFixture();
        when(fixture.services.dockerNetwork()).thenReturn(Optional.of("global_network"));
        when(fixture.currentContainerNetworkResolver.resolveNetworkName()).thenReturn(Optional.of("compose_default"));

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withDockerNetwork(Optional.empty())
                .build();

        assertEquals("global_network", spec.networkMode());
    }

    @Test
    void withDockerNetwork_inheritsCurrentContainerNetworkWhenNoConfigIsSet() {
        TestFixture fixture = new TestFixture();
        when(fixture.currentContainerNetworkResolver.resolveNetworkName()).thenReturn(Optional.of("avoxx-network"));

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withDockerNetwork(Optional.empty())
                .build();

        assertEquals("avoxx-network", spec.networkMode());
    }

    @Test
    void withEmbeddedDns_appendsFallbackResolversAfterFlociIp() {
        TestFixture fixture = new TestFixture();
        when(fixture.embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.4"));

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withEmbeddedDns()
                .build();

        assertEquals(List.of("172.18.0.4", "8.8.8.8", "8.8.4.4"), spec.dnsServers());
    }

    @Test
    void withEmbeddedDns_omitsFallbackResolversWhenDisabled() {
        TestFixture fixture = new TestFixture();
        when(fixture.embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.4"));
        when(fixture.dns.containerFallbackEnabled()).thenReturn(false);

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withEmbeddedDns()
                .build();

        assertEquals(List.of("172.18.0.4"), spec.dnsServers());
    }

    @Test
    void withEmbeddedDns_noOpWhenEmbeddedDnsNotRunning() {
        TestFixture fixture = new TestFixture();
        // getServerIp() empty (default) — no Floci IP, so no fallbacks are injected either.

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withEmbeddedDns()
                .build();

        assertEquals(List.of(), spec.dnsServers());
    }

    @Test
    void withCgroupnsModeRecordsDockerNamespaceMode() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withCgroupnsMode("host")
                .build();

        assertEquals("host", spec.cgroupnsMode());
    }

    private static class TestFixture {
        final EmulatorConfig config = mock(EmulatorConfig.class);
        final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        final EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        final EmulatorConfig.DnsConfig dns = mock(EmulatorConfig.DnsConfig.class);
        final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        final EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        final CurrentContainerNetworkResolver currentContainerNetworkResolver =
                mock(CurrentContainerNetworkResolver.class);
        final ContainerBuilder builder =
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer, currentContainerNetworkResolver);

        TestFixture() {
            when(config.services()).thenReturn(services);
            when(services.dockerNetwork()).thenReturn(Optional.empty());
            when(config.docker()).thenReturn(docker);
            when(docker.logMaxSize()).thenReturn("10m");
            when(docker.logMaxFile()).thenReturn("3");
            when(config.dns()).thenReturn(dns);
            when(dns.containerFallbackEnabled()).thenReturn(true);
            when(dns.containerFallbackServers()).thenReturn(List.of("8.8.8.8", "8.8.4.4"));
            when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        }
    }
}
