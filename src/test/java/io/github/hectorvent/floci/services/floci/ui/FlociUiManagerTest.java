package io.github.hectorvent.floci.services.floci.ui;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlociUiManagerTest {

    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);

    private FlociUiManager newManager() {
        return new FlociUiManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                config,
                mock(RegionResolver.class));
    }

    @Test
    void containerizedUsesResolvedContainerIpNotHostDockerInternal() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("172.24.0.2");

        assertEquals("http://172.24.0.2:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void explicitHostnameWinsWhenContainerized() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");

        assertEquals("http://floci:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void onHostFallsBackToHostDockerInternal() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("http://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void probeUsesSidecarContainerIpWhenContainerized() {
        // In a container the published host port is not reachable via localhost; the
        // probe must target the sidecar's container IP on the shared Docker network.
        EndpointInfo endpoint = new EndpointInfo("10.88.0.20", 4500);

        assertEquals("http://10.88.0.20:4500/", newManager().resolveProbeUrl(endpoint, 4500));
    }

    @Test
    void probeUsesLocalhostHostPortNatively() {
        EndpointInfo endpoint = new EndpointInfo("localhost", 4500);

        assertEquals("http://localhost:4500/", newManager().resolveProbeUrl(endpoint, 4500));
    }

    @Test
    void probeFallsBackToLocalhostWhenEndpointMissing() {
        assertEquals("http://localhost:4500/", newManager().resolveProbeUrl(null, 4500));
    }

    @Test
    void hostPortUsesBoundEndpointPortNatively() {
        // Native mode: EndpointInfo carries the actual bound host port, which may differ
        // from the requested port when dynamic allocation (port=0) is used.
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        EndpointInfo endpoint = new EndpointInfo("localhost", 49160);

        assertEquals(49160, newManager().resolveHostPort(endpoint, 0));
    }

    @Test
    void hostPortKeepsConfiguredPublishedPortWhenContainerized() {
        // Container mode: EndpointInfo carries the sidecar's internal port (4500), not the
        // host binding, so the configured published port must win for the browser redirect.
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        EndpointInfo endpoint = new EndpointInfo("10.88.0.20", 4500);

        assertEquals(8080, newManager().resolveHostPort(endpoint, 8080));
    }

    @Test
    void hostPortFallsBackToConfiguredWhenEndpointMissing() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        assertEquals(4500, newManager().resolveHostPort(null, 4500));
    }

    @Test
    void startFailureFromMissingImageKeepsPullGuidance() {
        // A genuinely missing image (docker-java's pull-callback wrapper) — keep "docker pull".
        Exception e = new DockerClientException("Could not pull image: not found");

        String msg = FlociUiManager.describeStartFailure("floci/floci-ui:latest", e);

        assertTrue(msg.contains("docker pull floci/floci-ui:latest"),
                "missing-image failure should still suggest docker pull, was: " + msg);
        assertTrue(msg.contains("unavailable"));
    }

    @Test
    void startFailureFromMissingImageNotFoundKeepsPullGuidance() {
        Exception e = new NotFoundException("no such image");

        String msg = FlociUiManager.describeStartFailure("floci/floci-ui:latest", e);

        assertTrue(msg.contains("docker pull floci/floci-ui:latest"), msg);
    }

    @Test
    void startFailureFromUnreachableSocketDoesNotBlameImage() {
        // The Podman/SELinux symptom: docker-java's Apache transport wraps a denied
        // Unix-socket connect as RuntimeException -> BindException. Must NOT suggest a pull.
        Exception e = new RuntimeException(new BindException("Permission denied"));

        String msg = FlociUiManager.describeStartFailure("floci/floci-ui:latest", e);

        assertFalse(msg.contains("docker pull"),
                "socket-permission failure must not be reported as a missing image, was: " + msg);
        assertTrue(msg.contains("could not reach the container runtime"), msg);
        assertTrue(msg.contains("Permission denied"), msg);
    }

    @Test
    void startFailureFromPortConflictDoesNotBlameImage() {
        // Daemon error (e.g. port already in use) — report it as-is, no pull guidance.
        Exception e = new RuntimeException(
                "Status 500: listen tcp :4500: bind: address already in use");

        String msg = FlociUiManager.describeStartFailure("floci/floci-ui:latest", e);

        assertFalse(msg.contains("docker pull"), msg);
        assertTrue(msg.contains("address already in use"), msg);
    }

    @Test
    void usesHttpsSchemeWhenTlsEnabled() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("https://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }
}
