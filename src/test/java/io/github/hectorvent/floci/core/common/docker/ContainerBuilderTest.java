package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.DeviceRequest;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void withUserAndGroupAddRoundTripIntoSpec() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withUser("1001:1001")
                .withGroupAdd("1001")
                .withGroupAdd("2000")
                .withGroupAdd("   ")   // blank ignored
                .build();

        assertEquals("1001:1001", spec.user());
        assertEquals(List.of("1001", "2000"), spec.groupAdd());
    }

    @Test
    void userAndGroupAddDefaultToUnset() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine").build();

        assertNull(spec.user());
        assertEquals(List.of(), spec.groupAdd());
    }

    @Test
    void withGpuCountRequestsThatManyGpus() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withGpuCount(2)
                .build();

        assertEquals(1, spec.deviceRequests().size());
        DeviceRequest request = spec.deviceRequests().get(0);
        assertEquals(2, request.getCount());
        assertEquals(List.of(List.of("gpu")), request.getCapabilities());
        // Docker rejects a request carrying both a count and explicit ids.
        assertNull(request.getDeviceIds());
        assertNull(request.getDriver());
    }

    @Test
    void withAllGpusRequestsEveryDevice() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withAllGpus()
                .build();

        assertEquals(-1, spec.deviceRequests().get(0).getCount());
        assertEquals(List.of(List.of("gpu")), spec.deviceRequests().get(0).getCapabilities());
    }

    @Test
    void withGpuDeviceIdsPinsSpecificDevicesAndLeavesCountUnset() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withGpuDeviceIds(List.of("0", "GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"))
                .build();

        DeviceRequest request = spec.deviceRequests().get(0);
        assertEquals(List.of("0", "GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"), request.getDeviceIds());
        assertEquals(List.of(List.of("gpu")), request.getCapabilities());
        assertNull(request.getCount());
    }

    @Test
    void withCdiDevicesSelectsDevicesByCdiName() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine")
                .withCdiDevices(List.of("nvidia.com/gpu=GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"))
                .build();

        DeviceRequest request = spec.deviceRequests().get(0);
        assertEquals("cdi", request.getDriver());
        assertEquals(List.of("nvidia.com/gpu=GPU-1fc572c4-6cd7-0e21-dceb-fa71af82eed5"), request.getDeviceIds());
        // The CDI name already names the device, so no capability is negotiated.
        assertNull(request.getCapabilities());
        assertNull(request.getCount());
    }

    @Test
    void containersRequestNoDevicesByDefault() {
        TestFixture fixture = new TestFixture();

        ContainerSpec spec = fixture.builder.newContainer("alpine").build();

        assertEquals(List.of(), spec.deviceRequests());
        assertFalse(spec.hasDeviceRequests());
    }

    @Test
    void gpuCountMustBePositive() {
        TestFixture fixture = new TestFixture();
        ContainerBuilder.Builder builder = fixture.builder.newContainer("alpine");

        assertThrows(IllegalArgumentException.class, () -> builder.withGpuCount(0));
        assertThrows(IllegalArgumentException.class, () -> builder.withGpuCount(-1));
    }

    @Test
    void deviceIdsMustBePresentAndNonBlank() {
        TestFixture fixture = new TestFixture();
        ContainerBuilder.Builder builder = fixture.builder.newContainer("alpine");

        assertThrows(IllegalArgumentException.class, () -> builder.withGpuDeviceIds(List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.withGpuDeviceIds(List.of("  ")));
    }

    @Test
    void cdiDeviceNameMustBeFullyQualified() {
        TestFixture fixture = new TestFixture();
        ContainerBuilder.Builder builder = fixture.builder.newContainer("alpine");

        // A device path is the mistake this guards: the daemon would otherwise fail the
        // start with an unresolvable-device error that does not name the real problem.
        assertThrows(IllegalArgumentException.class, () -> builder.withCdiDevices(List.of("/dev/nvidia0")));
        assertThrows(IllegalArgumentException.class, () -> builder.withCdiDevices(List.of("nvidia.com/gpu")));
    }

    @Test
    void countAndExplicitDevicesAreMutuallyExclusive() {
        TestFixture fixture = new TestFixture();

        assertThrows(IllegalStateException.class,
                () -> fixture.builder.newContainer("alpine").withGpuCount(1).withAllGpus());
        assertThrows(IllegalStateException.class,
                () -> fixture.builder.newContainer("alpine").withGpuCount(1).withGpuDeviceIds(List.of("0")));
        assertThrows(IllegalStateException.class,
                () -> fixture.builder.newContainer("alpine")
                        .withCdiDevices(List.of("nvidia.com/gpu=all"))
                        .withGpuCount(1));
    }

    @Test
    void imageRegistryBasePrefixesEveryContainerImage() {
        TestFixture fixture = new TestFixture();
        when(fixture.docker.imageRegistryBase()).thenReturn(Optional.of("ghcr.io/floci-io/mirror/"));

        assertEquals(
                "ghcr.io/floci-io/mirror/postgres:16-alpine",
                fixture.builder.newContainer("postgres:16-alpine").build().image());
        assertEquals(
                "ghcr.io/floci-io/mirror/public.ecr.aws/docker/library/ubuntu:24.04",
                fixture.builder.newContainer("public.ecr.aws/docker/library/ubuntu:24.04").build().image());
    }

    @Test
    void imageRegistryBaseDoesNotDoublePrefixImagesAlreadyUnderBase() {
        TestFixture fixture = new TestFixture();
        when(fixture.docker.imageRegistryBase()).thenReturn(Optional.of("ghcr.io/floci-io/mirror"));

        ContainerSpec spec = fixture.builder
                .newContainer("ghcr.io/floci-io/mirror/floci/ami-ubuntu:24.04-arm64")
                .build();

        assertEquals("ghcr.io/floci-io/mirror/floci/ami-ubuntu:24.04-arm64", spec.image());
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
            when(docker.imageRegistryBase()).thenReturn(Optional.empty());
            when(config.dns()).thenReturn(dns);
            when(dns.containerFallbackEnabled()).thenReturn(true);
            when(dns.containerFallbackServers()).thenReturn(List.of("8.8.8.8", "8.8.4.4"));
            when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        }
    }
}
