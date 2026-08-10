package io.github.hectorvent.floci.services.floci.ui;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlociUiManagerTest {

    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
    private final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
    private final EmulatorConfig.UiServiceConfig ui = mock(EmulatorConfig.UiServiceConfig.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);

    /** Wires config.services().ui() with the defaults every UI test starts from. */
    private void withUiConfig() {
        when(config.services()).thenReturn(services);
        when(services.ui()).thenReturn(ui);
        when(ui.endpoint()).thenReturn(Optional.empty());
        when(ui.insecureSkipTlsVerify()).thenReturn(false);
    }

    private FlociUiManager newManager() {
        return new FlociUiManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                config,
                regionResolver);
    }

    @Test
    void containerizedUsesResolvedContainerIpNotHostDockerInternal() {
        withUiConfig();
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
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");

        assertEquals("http://floci:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void onHostFallsBackToHostDockerInternal() {
        withUiConfig();
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
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("https://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }

    // --- endpoint drift: an adopted sidecar must not keep pointing at a dead Floci ---

    @Test
    void endpointDriftDetectedWhenAdoptedContainerPointsAtAnotherAddress() {
        // The sidecar outlives Floci and is re-adopted after a restart, but its
        // FLOCI_ENDPOINT was baked in at create time against the previous container IP.
        List<String> staleEnv = List.of("PORT=4500", "FLOCI_ENDPOINT=http://10.88.4.98:4566");

        assertTrue(FlociUiManager.endpointDrifted(staleEnv, "http://10.88.3.252:4566"),
                "an adopted sidecar pointing at a previous Floci IP must be treated as drifted");
    }

    @Test
    void noEndpointDriftWhenAdoptedContainerAlreadyMatches() {
        List<String> env = List.of("PORT=4500", "FLOCI_ENDPOINT=http://10.88.3.252:4566");

        assertFalse(FlociUiManager.endpointDrifted(env, "http://10.88.3.252:4566"),
                "a sidecar already pointing at the current endpoint must be adopted as-is");
    }

    @Test
    void endpointDriftDetectedWhenAdoptedContainerHasNoEndpointAtAll() {
        // Missing is not "fine" — it is unknown, and adopting it would strand the UI.
        assertTrue(FlociUiManager.endpointDrifted(List.of("PORT=4500"), "http://10.88.3.252:4566"));
        assertTrue(FlociUiManager.endpointDrifted(null, "http://10.88.3.252:4566"));
    }

    @Test
    void endpointDriftDetectedAcrossSchemeChange() {
        // TLS toggled between runs: same host, different scheme, still unreachable.
        List<String> env = List.of("FLOCI_ENDPOINT=http://10.88.3.252:4566");

        assertTrue(FlociUiManager.endpointDrifted(env, "https://10.88.3.252:4566"));
    }

    @Test
    void unreadableEnvironmentAdoptsRatherThanDestroyingAPossiblyHealthySidecar() {
        // A failed inspect says nothing about the sidecar. Treating "I could not read it" as
        // drift would let one transient container-runtime hiccup destroy a working sidecar.
        assertFalse(FlociUiManager.shouldReplace(Optional.empty(), "http://10.88.3.252:4566"));
    }

    @Test
    void readableEnvironmentStillReplacesOnDrift() {
        Optional<List<String>> env = Optional.of(List.of("FLOCI_ENDPOINT=http://10.88.4.98:4566"));

        assertTrue(FlociUiManager.shouldReplace(env, "http://10.88.3.252:4566"));
    }

    @Test
    void readableMatchingEnvironmentIsAdopted() {
        Optional<List<String>> env = Optional.of(List.of("FLOCI_ENDPOINT=http://10.88.3.252:4566"));

        assertFalse(FlociUiManager.shouldReplace(env, "http://10.88.3.252:4566"));
    }

    // --- re-arming a lost sidecar (must not churn a healthy one) ---

    @Test
    void removedSidecarReArmsSoTheDashboardRecoversOnItsOwn() {
        assertTrue(FlociUiManager.shouldReArm(ContainerPresence.ABSENT));
    }

    @Test
    void exitedSidecarReArms() {
        assertTrue(FlociUiManager.shouldReArm(ContainerPresence.STOPPED));
    }

    @Test
    void bootingSidecarThatIsNotAnsweringYetIsLeftAlone() {
        // The probe fails for the whole of a cold boot. Re-arming here would re-adopt the
        // container on every poll — a log-stream reattach per poll, forever, for a sidecar
        // that was about to come up on its own.
        assertFalse(FlociUiManager.shouldReArm(ContainerPresence.RUNNING));
    }

    @Test
    void unknownPresenceIsNotTreatedAsAMissingSidecar() {
        assertFalse(FlociUiManager.shouldReArm(ContainerPresence.UNKNOWN));
    }

    // --- explicit endpoint override ---

    @Test
    void configuredEndpointOverrideWinsOverDerivation() {
        withUiConfig();
        when(ui.endpoint()).thenReturn(Optional.of("http://10.88.3.252:4566"));
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        assertEquals("http://10.88.3.252:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void blankEndpointOverrideFailsFastRatherThanSilentlyFallingBack() {
        withUiConfig();
        when(ui.endpoint()).thenReturn(Optional.of("   "));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> newManager().resolveFlociEndpoint());
        assertTrue(e.getMessage().contains("floci.services.ui.endpoint"), e.getMessage());
    }

    @Test
    void malformedEndpointOverrideFailsFast() {
        withUiConfig();
        when(ui.endpoint()).thenReturn(Optional.of("10.88.3.252:4566"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> newManager().resolveFlociEndpoint());
        assertTrue(e.getMessage().contains("http://"), e.getMessage());
    }

    // --- TLS trust for the sidecar's Node/Bun proxy ---

    @Test
    void insecureSkipTlsVerifyInjectsNodeTlsRejectUnauthorized() {
        // Floci's self-signed cert has no IP SAN for its own container IP, so the sidecar
        // fails ERR_TLS_CERT_ALTNAME_INVALID even with the CA trusted. Only disabling
        // verification outright clears both the chain and the altname check.
        withUiConfig();
        when(ui.insecureSkipTlsVerify()).thenReturn(true);
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        assertTrue(newManager().injectedEnv().contains("NODE_TLS_REJECT_UNAUTHORIZED=0"));
    }

    @Test
    void tlsVerificationLeftIntactByDefault() {
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        List<String> env = newManager().injectedEnv();
        assertFalse(env.stream().anyMatch(v -> v.startsWith("NODE_TLS_REJECT_UNAUTHORIZED")),
                "TLS verification must stay on unless explicitly opted out, was: " + env);
    }
}
