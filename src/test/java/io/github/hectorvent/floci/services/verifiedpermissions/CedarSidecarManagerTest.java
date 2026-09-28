package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CedarSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig.VerifiedPermissionsServiceConfig verifiedPermissions =
            mock(EmulatorConfig.VerifiedPermissionsServiceConfig.class);
    private final EmulatorConfig config = config(verifiedPermissions);
    private HttpServer fakeSidecar;

    @AfterEach
    void stopFakeSidecar() {
        if (fakeSidecar != null) {
            fakeSidecar.stop(0);
        }
    }

    @Test
    void configuredUrlSkipsContainerManagement() {
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.of("http://cedar.internal:8180/"));

        CedarSidecarManager manager = manager();

        assertEquals("http://cedar.internal:8180", manager.ensureReady());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void staleManagedEndpointIsDiscarded() throws Exception {
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.empty());

        CedarSidecarManager manager = manager();
        setField(manager, "resolvedUrl", "http://127.0.0.1:1");
        setField(manager, "containerId", "stale-cedar");

        assertFalse(manager.isAvailable());
        verify(lifecycleManager).stopAndRemove("stale-cedar", null);
        assertNull(getField(manager, "resolvedUrl"));
        assertNull(getField(manager, "containerId"));
    }

    @Test
    void contractOneSidecarIsAvailable() throws Exception {
        String url = startFakeSidecar(200, "{\"status\":\"ok\",\"name\":\"cedar\",\"version\":\"1.0.0\",\"contract\":\"1\"}");
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.of(url));

        CedarSidecarManager manager = manager();

        assertTrue(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void otherContractMajorFailsFastNamingTheFix() throws Exception {
        String url = startFakeSidecar(200, "{\"status\":\"ok\",\"name\":\"cedar\",\"version\":\"2.3.0\",\"contract\":\"2\"}");
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.of(url));
        when(verifiedPermissions.cedarImage()).thenReturn("floci/floci-sidecar-cedar:1.1.0");

        CedarSidecarManager manager = manager();

        IllegalStateException available = assertThrows(IllegalStateException.class, manager::isAvailable);
        IllegalStateException ready = assertThrows(IllegalStateException.class, manager::ensureReady);
        assertThat(available.getMessage(), containsString("speaks sidecar contract 2"));
        assertThat(available.getMessage(), containsString("requires contract 1"));
        assertThat(available.getMessage(), containsString("cedar 2.3.0"));
        assertThat(ready.getMessage(), containsString(CedarSidecarManager.IMAGE_ENV));
        assertThat(ready.getMessage(), containsString("floci/floci-sidecar-cedar:1.1.0"));
        assertThat(ready.getMessage(), containsString(CedarSidecarManager.URL_ENV));
        assertNull(getField(manager, "resolvedUrl"));
    }

    @Test
    void preContractSidecarWithTextHealthIsAccepted() throws Exception {
        String url = startFakeSidecar(200, "ok");
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.of(url));

        CedarSidecarManager manager = manager();

        assertTrue(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
    }

    @Test
    void unhealthySidecarIsNotReadyRatherThanWrong() throws Exception {
        String url = startFakeSidecar(503, "{\"status\":\"starting\",\"contract\":\"2\"}");
        when(verifiedPermissions.cedarUrl()).thenReturn(Optional.of(url));

        CedarSidecarManager manager = manager();

        assertFalse(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
    }

    private CedarSidecarManager manager() {
        return new CedarSidecarManager(containerBuilder, lifecycleManager, config, new ObjectMapper());
    }

    private static EmulatorConfig config(EmulatorConfig.VerifiedPermissionsServiceConfig verifiedPermissions) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.verifiedpermissions()).thenReturn(verifiedPermissions);
        return config;
    }

    private String startFakeSidecar(int status, String healthBody) throws IOException {
        fakeSidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeSidecar.createContext("/health", exchange -> {
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
