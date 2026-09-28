package io.github.hectorvent.floci.services.codeartifact;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReposiliteSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig.CodeArtifactServiceConfig codeArtifact = mock(EmulatorConfig.CodeArtifactServiceConfig.class);
    private final EmulatorConfig config = config(codeArtifact);
    private HttpServer fakeSidecar;

    @AfterEach
    void stopFakeSidecar() {
        if (fakeSidecar != null) {
            fakeSidecar.stop(0);
        }
    }

    @Test
    void configuredUrlSkipsContainerManagementAndUsesTheConfiguredToken() throws IOException {
        String url = startFakeSidecar(200, "{\"status\":\"UP\"}");
        when(codeArtifact.mavenUrl()).thenReturn(Optional.of(url));
        when(codeArtifact.mavenToken()).thenReturn(Optional.of("alice:s3cr3t"));

        ReposiliteSidecarManager manager = manager();

        assertEquals(url, manager.ensureReady());
        assertThat(manager.basicAuthHeader(), equalTo("Basic " + Base64.getEncoder()
                .encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8))));
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void configuredUrlWithoutAMatchingTokenFailsFast() {
        when(codeArtifact.mavenUrl()).thenReturn(Optional.of("http://reposilite.internal:8080"));
        when(codeArtifact.mavenToken()).thenReturn(Optional.empty());

        ReposiliteSidecarManager manager = manager();

        IllegalStateException e = assertThrows(IllegalStateException.class, manager::ensureReady);
        assertThat(e.getMessage(), equalTo(ReposiliteSidecarManager.URL_ENV
                + " is set but the matching token property (name:secret) is missing or malformed."));
    }

    @Test
    void staleManagedEndpointIsDiscardedAndFallsBackToTheConfiguredUrl() throws Exception {
        String freshUrl = startFakeSidecar(200, "{\"status\":\"UP\"}");
        when(codeArtifact.mavenUrl()).thenReturn(Optional.of(freshUrl));
        when(codeArtifact.mavenToken()).thenReturn(Optional.of("alice:s3cr3t"));
        ReposiliteSidecarManager manager = manager();
        setField(manager, "resolvedUrl", "http://127.0.0.1:1");
        setField(manager, "containerId", "stale-reposilite");

        assertEquals(freshUrl, manager.ensureReady());

        verify(lifecycleManager).stopAndRemove("stale-reposilite", null);
        assertNull(getField(manager, "containerId"));
        verifyNoInteractions(containerBuilder);
    }

    @Test
    void isStartedIsFalseForAManagedContainerThatWasNeverStartedAndNeverStartsOneItself() {
        when(codeArtifact.mavenUrl()).thenReturn(Optional.empty());
        ReposiliteSidecarManager manager = manager();

        assertFalse(manager.isStarted());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void isStartedIsTrueForAConfiguredUrlEvenBeforeThisProcessEverResolvesIt() {
        // A managed container keeps no volume, so "never started" really does mean "nothing to
        // release." An external, pre-configured instance's lifecycle is independent of this
        // process: it may already hold real data from an earlier Floci run, so this process not
        // having resolved it yet must not be read the same way.
        when(codeArtifact.mavenUrl()).thenReturn(Optional.of("http://reposilite.internal:8080"));
        ReposiliteSidecarManager manager = manager();

        assertTrue(manager.isStarted());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void stopManagedContainersStopsAndForgetsTheManagedContainer() throws Exception {
        ReposiliteSidecarManager manager = manager();
        setField(manager, "containerId", "running-reposilite");

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemove("running-reposilite", null);
    }

    @Test
    void stopManagedContainersIsANoOpWhenNothingWasEverStarted() {
        ReposiliteSidecarManager manager = manager();

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    private ReposiliteSidecarManager manager() {
        return new ReposiliteSidecarManager(containerBuilder, lifecycleManager, config, "unused-managed-secret");
    }

    private static EmulatorConfig config(EmulatorConfig.CodeArtifactServiceConfig codeArtifact) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.codeartifact()).thenReturn(codeArtifact);
        return config;
    }

    private String startFakeSidecar(int status, String healthBody) throws IOException {
        fakeSidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeSidecar.createContext("/api/status/health", exchange -> {
            byte[] bytes = healthBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fakeSidecar.start();
        return "http://127.0.0.1:" + fakeSidecar.getAddress().getPort();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
