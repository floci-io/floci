package io.github.hectorvent.floci.services.floci.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlociUiManagerTest {

    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
    private final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
    private final EmulatorConfig.UiServiceConfig ui = mock(EmulatorConfig.UiServiceConfig.class);

    /** The profile floci-ui resolves to, which most of these tests exercise. */
    private static final ConsoleProfile FLOCI_UI = ConsoleProfileResolver.builtIn("floci/floci-ui:latest");

    /** Wires config.services().ui() with the defaults every console test starts from. */
    private void withUiConfig() {
        when(config.services()).thenReturn(services);
        when(services.ui()).thenReturn(ui);
        when(ui.endpoint()).thenReturn(Optional.empty());
        when(ui.insecureSkipTlsVerify()).thenReturn(false);
        when(ui.internalPort()).thenReturn(OptionalInt.empty());
        when(ui.endpointEnv()).thenReturn(Optional.empty());
        when(ui.extraEnv()).thenReturn(Optional.empty());
        when(ui.statusPath()).thenReturn(Optional.empty());
        when(ui.statusReadyField()).thenReturn(Optional.empty());
        when(ui.statusReadyValue()).thenReturn(Optional.empty());
        when(ui.statusUnavailableValue()).thenReturn(Optional.empty());
    }

    private FlociUiManager newManager() {
        return newManager(mock(ContainerBuilder.class));
    }

    private FlociUiManager newManager(ContainerBuilder containerBuilder) {
        return new FlociUiManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                new LaunchedContainerAwsEnv(mock(ContainerReachableEndpoint.class)),
                config,
                regionResolver,
                new ObjectMapper());
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
    void tlsEnabledDerivesHttpForAnIpLiteralHost() {
        // The self-signed certificate carries no IP SAN for the container address the resolver
        // returns, so https:// to it can only ever fail altname verification. Port 4566 does
        // HTTP/HTTPS protocol detection, so http:// reaches the same listener with TLS left on.
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("10.88.3.252");

        assertEquals("http://10.88.3.252:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void tlsEnabledKeepsHttpsForANamedHostTheCertificateCovers() {
        // host.docker.internal is a DNS SAN on the self-signed certificate. Nothing to work
        // around, so the downgrade must not reach it.
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("https://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void tlsEnabledKeepsHttpsForAnIpLiteralWhenVerificationIsSkipped() {
        // An operator who opted into skipping verification has said they want TLS on this hop;
        // the altname gap no longer blocks it, so honour the explicit choice.
        withUiConfig();
        when(ui.insecureSkipTlsVerify()).thenReturn(true);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("10.88.3.252");

        assertEquals("https://10.88.3.252:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void anIpv6LiteralHostIsBracketedSoTheEndpointStaysAParseableUrl() {
        // Without brackets the port colon is indistinguishable from the address colons and the
        // sidecar gets a URL no client can parse.
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("fd00::2");

        assertEquals("http://[fd00::2]:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void probeUsesSidecarContainerIpWhenContainerized() {
        // In a container the published host port is not reachable via localhost; the
        // probe must target the sidecar's container IP on the shared Docker network.
        withUiConfig();
        EndpointInfo endpoint = new EndpointInfo("10.88.0.20", 4500);

        assertEquals("http://10.88.0.20:4500/api/clouds/aws/status",
                newManager().resolveProbeUrl(FLOCI_UI, endpoint, 4500));
    }

    @Test
    void probeUsesLocalhostHostPortNatively() {
        withUiConfig();
        EndpointInfo endpoint = new EndpointInfo("localhost", 4500);

        assertEquals("http://localhost:4500/api/clouds/aws/status",
                newManager().resolveProbeUrl(FLOCI_UI, endpoint, 4500));
    }

    @Test
    void probeFallsBackToLocalhostWhenEndpointMissing() {
        withUiConfig();
        assertEquals("http://localhost:4500/api/clouds/aws/status",
                newManager().resolveProbeUrl(FLOCI_UI, null, 4500));
    }

    @Test
    void statusIsReadyWhenSidecarCanReachFloci() throws Exception {
        HttpServer server = startRuntimeStatusServer("""
                {"runtime":"reachable","endpoint":"http://host.docker.internal:4566"}
                """);
        try {
            FlociUiManager manager = adoptSidecar(server.getAddress().getPort());

            FlociUiManager.UiStatus status = manager.status();

            assertTrue(status.started());
            assertTrue(status.ready());
            assertNull(status.error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void statusSurfacesSidecarRuntimeFailure() throws Exception {
        HttpServer server = startRuntimeStatusServer("""
                {
                  "runtime":"unavailable",
                  "endpoint":"http://10.88.4.98:4566",
                  "error":"ECONNREFUSED"
                }
                """);
        try {
            FlociUiManager manager = adoptSidecar(server.getAddress().getPort());

            FlociUiManager.UiStatus status = manager.status();

            assertTrue(status.started());
            assertFalse(status.ready());
            assertTrue(status.error().contains("http://10.88.4.98:4566"), status.error());
            assertTrue(status.error().contains("ECONNREFUSED"), status.error());
        } finally {
            server.stop(0);
        }
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
        // AWS_ENDPOINT_URL was baked in at create time against the previous container IP.
        List<String> staleEnv = List.of("PORT=4500", "AWS_ENDPOINT_URL=http://10.88.4.98:4566");

        assertTrue(FlociUiManager.endpointDrifted(staleEnv, "http://10.88.3.252:4566"),
                "an adopted sidecar pointing at a previous Floci IP must be treated as drifted");
    }

    @Test
    void noEndpointDriftWhenAdoptedContainerAlreadyMatches() {
        List<String> env = List.of("PORT=4500", "AWS_ENDPOINT_URL=http://10.88.3.252:4566");

        assertFalse(FlociUiManager.endpointDrifted(env, "http://10.88.3.252:4566"),
                "a sidecar already pointing at the current endpoint must be adopted as-is");
    }

    @Test
    void endpointDriftDetectedWhenAdoptedContainerHasNoEndpointAtAll() {
        // Missing is not "fine": it is unknown, and adopting it would strand the console.
        assertTrue(FlociUiManager.endpointDrifted(List.of("PORT=4500"), "http://10.88.3.252:4566"));
        assertTrue(FlociUiManager.endpointDrifted(null, "http://10.88.3.252:4566"));
    }

    @Test
    void endpointDriftDetectedAcrossSchemeChange() {
        // TLS toggled between runs: same host, different scheme, still unreachable.
        List<String> env = List.of("AWS_ENDPOINT_URL=http://10.88.3.252:4566");

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
        Optional<List<String>> env = Optional.of(List.of("AWS_ENDPOINT_URL=http://10.88.4.98:4566"));

        assertTrue(FlociUiManager.shouldReplace(env, "http://10.88.3.252:4566"));
    }

    @Test
    void readableMatchingEnvironmentIsAdopted() {
        Optional<List<String>> env = Optional.of(List.of("AWS_ENDPOINT_URL=http://10.88.3.252:4566"));

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

        assertTrue(newManager().injectedEnv(ConsoleProfileResolver.contractV1())
                .contains("NODE_TLS_REJECT_UNAUTHORIZED=0"));
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

        List<String> env = newManager().injectedEnv(ConsoleProfileResolver.contractV1());
        assertFalse(env.stream().anyMatch(v -> v.startsWith("NODE_TLS_REJECT_UNAUTHORIZED")),
                "TLS verification must stay on unless explicitly opted out, was: " + env);
    }

    // --- re-arming after an async start must not get stuck ---

    @Test
    void statusReArmsAfterAnAsyncStartActuallyRestartsTheSidecar() {
        // The sidecar's first start goes through ensureStartedAsync() (the interstitial-page
        // path). Once it is gone and status() wants to bring it back, the second restart is
        // also kicked through ensureStartedAsync() — that second kick must not be silently
        // dropped just because the first one already ran to completion.
        withUiConfig();
        when(ui.enabled()).thenReturn(true);
        when(ui.containerName()).thenReturn("floci-ui");
        when(ui.endpoint()).thenReturn(Optional.of("http://custom:4566"));
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("abc123");
        when(lifecycleManager.findByName("floci-ui")).thenReturn(Optional.of(existing));
        when(lifecycleManager.containerEnv("abc123")).thenReturn(Optional.empty());
        EndpointInfo endpoint = new EndpointInfo("localhost", 4500);
        when(lifecycleManager.adopt("abc123", List.of(4500)))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("abc123", Map.of(4500, endpoint)));
        // Running when it is first adopted, gone by the time status() checks on it.
        when(lifecycleManager.presenceOf("abc123"))
                .thenReturn(ContainerPresence.RUNNING, ContainerPresence.ABSENT);

        FlociUiManager manager = new FlociUiManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                new LaunchedContainerAwsEnv(mock(ContainerReachableEndpoint.class)),
                config,
                regionResolver,
                new ObjectMapper());

        manager.ensureStartedAsync();
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(lifecycleManager, times(1)).adopt("abc123", List.of(4500)));

        // The sidecar is gone (ABSENT) and unreachable (nothing listens on localhost:4500), so
        // status() must trigger a second async restart. That restart no longer re-adopts the dead
        // container, so the kick is observed by the start running a second time at all.
        manager.status();

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(lifecycleManager, times(2)).findByName("floci-ui"));
    }

    @Test
    void aStoppedSidecarIsRecreatedRatherThanAdoptedForever() {
        // Adopting an exited container is a loop: the probe fails, the container reads as gone,
        // the re-arm comes back here, and the same dead container is adopted again on every poll
        // with nothing ever starting it.
        assertTrue(FlociUiManager.mustRecreate(ContainerPresence.STOPPED));
        assertTrue(FlociUiManager.mustRecreate(ContainerPresence.ABSENT));
        assertFalse(FlociUiManager.mustRecreate(ContainerPresence.RUNNING),
                "a running sidecar must still be adopted, not churned");
        assertFalse(FlociUiManager.mustRecreate(ContainerPresence.UNKNOWN),
                "acting on an unknown is how a transient runtime hiccup destroys a healthy sidecar");
    }

    // --- a start that fails before the try block must not vanish silently ---

    @Test
    void ensureStartedRecordsTheFailureWhenTheContainerRuntimeIsUnreachable() {
        // findByName runs before the try/catch in ensureStarted(). If the container runtime is
        // unreachable it throws there, and the failure must still be captured for status() --
        // not escape uncaught (synchronous callers) or vanish into a discarded Future (the
        // ensureStartedAsync() path).
        withUiConfig();
        when(ui.enabled()).thenReturn(true);
        when(ui.containerName()).thenReturn("floci-ui");
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName("floci-ui"))
                .thenThrow(new com.github.dockerjava.api.exception.DockerClientException(
                        "Cannot connect to the Docker daemon"));

        FlociUiManager manager = new FlociUiManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                new LaunchedContainerAwsEnv(mock(ContainerReachableEndpoint.class)),
                config,
                regionResolver,
                new ObjectMapper());

        manager.ensureStarted();

        assertEquals(false, manager.status().started());
        assertTrue(manager.status().error() != null && manager.status().error().contains("Docker"),
                "expected a captured error mentioning the Docker runtime, was: " + manager.status().error());
    }

    @Test
    void endpointOverrideWithNoAuthorityFailsFast() {
        // "http://" and "http:///path" both pass a bare prefix check but carry no host, so the
        // sidecar would be started with an endpoint it can never connect to.
        withUiConfig();
        when(ui.endpoint()).thenReturn(Optional.of("http://"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> newManager().resolveFlociEndpoint());
        assertTrue(e.getMessage().contains("http://"), e.getMessage());
    }

    @Test
    void endpointOverrideWithPathOnlyAndNoAuthorityFailsFast() {
        withUiConfig();
        when(ui.endpoint()).thenReturn(Optional.of("http:///path"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> newManager().resolveFlociEndpoint());
        assertTrue(e.getMessage().contains("http:///path"), e.getMessage());
    }

    @Test
    void ensureStartedRecordsTheFailureFromAFreshStartWithNoExistingSidecarToAdopt() {
        // findByName finds nothing, so replaceIfEndpointDrifted (which records its own message)
        // never runs -- injectedEnv()/resolveFlociEndpoint() throws IllegalStateException
        // directly out of the try block on a blank/malformed override, and that catch must
        // still populate lastError instead of leaving status() reporting error=null forever.
        withUiConfig();
        when(ui.enabled()).thenReturn(true);
        when(ui.containerName()).thenReturn("floci-ui");
        when(ui.endpoint()).thenReturn(Optional.of("not-a-url"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName("floci-ui")).thenReturn(Optional.empty());

        FlociUiManager manager = new FlociUiManager(
                mock(ContainerBuilder.class, RETURNS_DEEP_STUBS),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                new LaunchedContainerAwsEnv(mock(ContainerReachableEndpoint.class)),
                config,
                regionResolver,
                new ObjectMapper());

        manager.ensureStarted();

        assertEquals(false, manager.status().started());
        assertTrue(manager.status().error() != null && manager.status().error().contains("http://"),
                "expected a captured error mentioning the required http:// scheme, was: "
                        + manager.status().error());
    }

    @Test
    void healthPathFollowsTheProfileSoAThirdPartyConsoleCanBeProbed() {
        // A console other than floci-ui answers the contract's /api/health, which floci-ui's
        // own /api/clouds/aws/status route would miss entirely.
        withUiConfig();

        assertEquals("http://10.88.0.20:8080/api/health",
                newManager().resolveProbeUrl(ConsoleProfileResolver.contractV1(),
                        new EndpointInfo("10.88.0.20", 8080), 8080));
    }

    @Test
    void statusPathWithoutALeadingSlashStillFormsAUrl() {
        withUiConfig();
        when(ui.statusPath()).thenReturn(Optional.of("api/health"));

        ConsoleProfile profile = ConsoleProfileResolver.withOverrides(ui, ConsoleProfileResolver.contractV1());

        assertEquals("http://localhost:8080/api/health",
                newManager().resolveProbeUrl(profile, null, 8080));
    }

    @Test
    void everyConsoleGetsTheStandardAwsEnvironmentAndItsListenPort() {
        // The contract's side of the bargain: an SDK-based console is configured entirely by its
        // ordinary endpoint and credential discovery, with no Floci-specific code.
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        List<String> env = newManager().injectedEnv(ConsoleProfileResolver.contractV1());

        assertTrue(env.contains("AWS_ENDPOINT_URL=http://floci:4566"), env.toString());
        assertTrue(env.contains("FLOCI_ENDPOINT=http://floci:4566"), env.toString());
        assertTrue(env.contains("FLOCI_HOSTNAME=floci"), env.toString());
        assertTrue(env.contains("AWS_REGION=us-east-1"), env.toString());
        assertTrue(env.contains("AWS_DEFAULT_REGION=us-east-1"), env.toString());
        assertTrue(env.contains("FLOCI_CLOUD=aws"), env.toString());
        assertTrue(env.contains("PORT=4500"), env.toString());
    }

    @Test
    void theConsoleIsNeverHandedFlocisOwnAwsCredentials() {
        // The baseline forwards Floci's ambient credentials when none are supplied, which is
        // acceptable for a workload the user wrote and not for a third-party console image. So
        // placeholders must be passed explicitly rather than left to that fallback.
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any()))
                .thenReturn(List.of("AWS_ACCESS_KEY_ID=test"));
        FlociUiManager manager = new FlociUiManager(
                mock(ContainerBuilder.class), lifecycleManager, logStreamer, containerDetector,
                mock(CurrentContainerNetworkResolver.class), dockerHostResolver,
                awsEnv, config, regionResolver, new ObjectMapper());

        List<String> env = manager.injectedEnv(ConsoleProfileResolver.contractV1());

        assertTrue(env.contains("AWS_ACCESS_KEY_ID=test"), env.toString());
        verify(awsEnv).sdkBaselineEnv("us-east-1", Optional.empty(), "http://floci:4566",
                Optional.of(new SessionCreds("test", "test", "test")));
    }

    @Test
    void aConsoleReadingItsOwnEndpointVariableGetsTheEndpointUnderThatNameToo() {
        withUiConfig();
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        ConsoleProfile profile = new ConsoleProfile(8080, "CONSOLE_API_URL", "/api/health",
                "status", "ok", "unavailable", "the console");

        List<String> env = newManager().injectedEnv(profile);

        assertTrue(env.contains("CONSOLE_API_URL=http://floci:4566"), env.toString());
        assertTrue(env.contains("AWS_ENDPOINT_URL=http://floci:4566"), env.toString());
        assertTrue(env.contains("PORT=8080"), env.toString());
    }

    @Test
    void skippingTlsVerificationIsAnnouncedInBothTheContractAndTheNodeForm() {
        withUiConfig();
        when(ui.insecureSkipTlsVerify()).thenReturn(true);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        List<String> env = newManager().injectedEnv(ConsoleProfileResolver.contractV1());

        assertTrue(env.contains("FLOCI_TLS_SKIP_VERIFY=1"), env.toString());
        assertTrue(env.contains("NODE_TLS_REJECT_UNAUTHORIZED=0"), env.toString());
    }

    @Test
    void extraEnvIsAppliedAndReplacesAnInjectedDefaultRatherThanDuplicatingIt() {
        withUiConfig();
        when(ui.extraEnv()).thenReturn(Optional.of(List.of(
                "STACKPORT_ALLOW_WRITES=false",
                "AWS_ACCESS_KEY_ID=custom")));
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        List<String> env = newManager().injectedEnv(ConsoleProfileResolver.contractV1());

        assertTrue(env.contains("STACKPORT_ALLOW_WRITES=false"), env.toString());
        assertTrue(env.contains("AWS_ACCESS_KEY_ID=custom"), env.toString());
        assertEquals(1, env.stream().filter(entry -> entry.startsWith("AWS_ACCESS_KEY_ID=")).count(),
                "an overridden variable must appear once, not twice: " + env);
    }

    @Test
    void extraEnvCannotOverrideThePortThePortBindingAndProbeAreBuiltFrom() {
        // PORT is structural: withPortBinding() and the readiness probe both read
        // profile.internalPort(). Honouring an override here would leave the console listening on
        // 8080 while Floci published and polled 4500, so the sidecar never becomes reachable.
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put(FlociUiManager.PORT_ENV, "4500");

        FlociUiManager.applyExtraEnv(env, Optional.of(List.of("PORT=8080")));

        assertEquals("4500", env.get(FlociUiManager.PORT_ENV),
                "extra-env must not move the port the binding and probe were built from");
    }

    @Test
    void extraEnvCannotOverrideTheEndpointAdoptionComparesAgainst() {
        // AWS_ENDPOINT_URL is what endpointDrifted() compares to spot a sidecar left addressing a
        // previous Floci. An override here reads as permanent drift, so the container would be
        // recreated on every check.
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put(FlociUiManager.CANONICAL_ENDPOINT_ENV, "http://floci:4566");

        FlociUiManager.applyExtraEnv(env, Optional.of(List.of("AWS_ENDPOINT_URL=http://elsewhere:9999")));

        assertEquals("http://floci:4566", env.get(FlociUiManager.CANONICAL_ENDPOINT_ENV),
                "extra-env must not move the endpoint adoption compares against");
    }

    @Test
    void reservingTheStructuralKeysDoesNotBlockTheEntriesBesideThem() {
        // The guard is two keys, not a general refusal: everything else still applies.
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put(FlociUiManager.PORT_ENV, "4500");

        FlociUiManager.applyExtraEnv(env, Optional.of(List.of(
                "PORT=8080",
                "MY_CONSOLE_FLAG=true")));

        assertEquals("4500", env.get(FlociUiManager.PORT_ENV));
        assertEquals("true", env.get("MY_CONSOLE_FLAG"));
    }

    @Test
    void extraEnvKeepsEveryEqualsSignAfterTheFirstInTheValue() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();

        FlociUiManager.applyExtraEnv(env, Optional.of(List.of("TOKEN=a=b==c")));

        assertEquals("a=b==c", env.get("TOKEN"));
    }

    @Test
    void malformedExtraEnvEntryIsDroppedRatherThanFailingTheStart() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("PORT", "4500");

        FlociUiManager.applyExtraEnv(env, Optional.of(java.util.Arrays.asList(
                "NOT_A_PAIR", "=orphan", "  ", null, "GOOD=yes")));

        assertEquals(Map.of("PORT", "4500", "GOOD", "yes"), env);
    }

    @Test
    void readinessFollowsTheConfiguredFieldAndValue() throws Exception {
        HttpServer server = startStatusServer("/api/health", """
                {"status":"ok","endpoint_url":"http://floci:4566"}
                """);
        try {
            FlociUiManager manager = adoptSidecar(
                    server.getAddress().getPort(), "/api/health", "status", "ok");

            assertTrue(manager.status().ready());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aReadyFieldOfNoneTreatsAnyTwoHundredAsReady() throws Exception {
        // All a plain liveness endpoint can report. Reading a field that is not in the
        // response would otherwise leave the console reported as never ready. The opt-out is
        // the literal "none": an empty value arrives as an absent property and would silently
        // mean "use the default field".
        HttpServer server = startStatusServer("/healthz", "OK");
        try {
            FlociUiManager manager = adoptSidecar(
                    server.getAddress().getPort(), "/healthz", "none", "reachable");

            assertTrue(manager.status().ready());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aConfiguredReadyFieldThatDoesNotMatchIsNotReady() throws Exception {
        HttpServer server = startStatusServer("/api/health", """
                {"status":"degraded"}
                """);
        try {
            FlociUiManager manager = adoptSidecar(
                    server.getAddress().getPort(), "/api/health", "status", "ok");

            assertFalse(manager.status().ready());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void driftIsDetectedAgainstTheCanonicalEndpointVariable() {
        List<String> env = List.of("AWS_ENDPOINT_URL=http://10.88.3.251:4566", "PORT=8080");

        assertTrue(FlociUiManager.endpointDrifted(env, "http://10.88.3.252:4566"));
        assertFalse(FlociUiManager.endpointDrifted(env, "http://10.88.3.251:4566"),
                "a sidecar already pointing at this Floci must be adopted, not recreated");
    }

    @Test
    void aSidecarFromAFlociThatOnlySetFlociEndpointIsRecreatedOnce() {
        // Before the console contract only FLOCI_ENDPOINT was injected. Such a container cannot be
        // checked for drift, so it is recreated on the first start after the upgrade and adopted
        // normally from then on.
        List<String> env = List.of("PORT=4500", "FLOCI_ENDPOINT=http://10.88.3.252:4566");

        assertTrue(FlociUiManager.endpointDrifted(env, "http://10.88.3.252:4566"),
                "a pre-contract sidecar must be recreated so it gains the canonical variable");
    }

    /** Adopts a console that resolves to the built-in floci-ui profile. */
    private FlociUiManager adoptSidecar(int port) {
        return adoptSidecar(port, "floci/floci-ui:latest",
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Adopts a console whose health shape is pinned by explicit configuration. */
    private FlociUiManager adoptSidecar(int port, String statusPath, String readyField, String readyValue) {
        return adoptSidecar(port, "acme/console:1", Optional.of(statusPath),
                Optional.of(readyField), Optional.of(readyValue));
    }

    private FlociUiManager adoptSidecar(int port, String image, Optional<String> statusPath,
                                        Optional<String> readyField, Optional<String> readyValue) {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.UiServiceConfig ui = mock(EmulatorConfig.UiServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ui()).thenReturn(ui);
        when(ui.enabled()).thenReturn(true);
        when(ui.image()).thenReturn(image);
        when(ui.containerName()).thenReturn("floci-ui");
        when(ui.port()).thenReturn(port);
        when(ui.internalPort()).thenReturn(OptionalInt.empty());
        when(ui.endpointEnv()).thenReturn(Optional.empty());
        when(ui.extraEnv()).thenReturn(Optional.empty());
        when(ui.statusPath()).thenReturn(statusPath);
        when(ui.statusReadyField()).thenReturn(readyField);
        when(ui.statusReadyValue()).thenReturn(readyValue);
        when(ui.statusUnavailableValue()).thenReturn(Optional.empty());
        when(ui.endpoint()).thenReturn(Optional.of("http://custom:4566"));

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.resolveImage(image)).thenReturn(image);

        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("ui-container");
        when(lifecycleManager.findByName("floci-ui")).thenReturn(Optional.of(existing));
        when(lifecycleManager.containerEnv("ui-container"))
                .thenReturn(Optional.of(List.of("AWS_ENDPOINT_URL=http://custom:4566")));
        when(lifecycleManager.adopt("ui-container", List.of(4500))).thenReturn(
                new ContainerInfo("ui-container", Map.of(4500, new EndpointInfo("127.0.0.1", port))));

        FlociUiManager manager = newManager(containerBuilder);
        manager.ensureStarted();
        return manager;
    }

    private static HttpServer startRuntimeStatusServer(String response) throws IOException {
        return startStatusServer("/api/clouds/aws/status", response);
    }

    private static HttpServer startStatusServer(String path, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }
}
