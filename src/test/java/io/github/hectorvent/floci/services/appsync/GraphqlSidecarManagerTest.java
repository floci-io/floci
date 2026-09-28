package io.github.hectorvent.floci.services.appsync;

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

class GraphqlSidecarManagerTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig.AppSyncServiceConfig appsync = mock(EmulatorConfig.AppSyncServiceConfig.class);
    private final EmulatorConfig config = config(appsync);
    private HttpServer fakeSidecar;

    @AfterEach
    void stopFakeSidecar() {
        if (fakeSidecar != null) {
            fakeSidecar.stop(0);
        }
    }

    @Test
    void configuredUrlSkipsContainerManagement() {
        when(appsync.graphqlUrl()).thenReturn(Optional.of("http://graphql.internal:8181/"));

        GraphqlSidecarManager manager = manager();

        assertEquals("http://graphql.internal:8181", manager.ensureReady());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void staleManagedEndpointIsDiscarded() throws Exception {
        when(appsync.graphqlUrl()).thenReturn(Optional.empty());

        GraphqlSidecarManager manager = manager();
        setField(manager, "resolvedUrl", "http://127.0.0.1:1");
        setField(manager, "containerId", "stale-graphql");

        assertFalse(manager.isAvailable());
        verify(lifecycleManager).stopAndRemove("stale-graphql", null);
        assertNull(getField(manager, "resolvedUrl"));
        assertNull(getField(manager, "containerId"));
    }

    @Test
    void contractOneSidecarIsAvailable() throws Exception {
        String url = startFakeSidecar(200, "{\"status\":\"ok\",\"name\":\"graphql\",\"version\":\"0.2.0\",\"contract\":\"1\"}");
        when(appsync.graphqlUrl()).thenReturn(Optional.of(url));

        GraphqlSidecarManager manager = manager();

        assertTrue(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }

    @Test
    void otherContractMajorFailsFastNamingTheFix() throws Exception {
        String url = startFakeSidecar(200, "{\"status\":\"ok\",\"name\":\"graphql\",\"version\":\"1.3.0\",\"contract\":\"2\"}");
        when(appsync.graphqlUrl()).thenReturn(Optional.of(url));
        when(appsync.graphqlImage()).thenReturn("floci/floci-sidecar-graphql:0.2.0");

        GraphqlSidecarManager manager = manager();

        IllegalStateException available = assertThrows(IllegalStateException.class, manager::isAvailable);
        IllegalStateException ready = assertThrows(IllegalStateException.class, manager::ensureReady);
        assertThat(available.getMessage(), containsString("speaks sidecar contract 2"));
        assertThat(available.getMessage(), containsString("requires contract 1"));
        assertThat(available.getMessage(), containsString("graphql 1.3.0"));
        assertThat(ready.getMessage(), containsString(GraphqlSidecarManager.IMAGE_ENV));
        assertThat(ready.getMessage(), containsString("floci/floci-sidecar-graphql:0.2.0"));
        assertThat(ready.getMessage(), containsString(GraphqlSidecarManager.URL_ENV));
        assertNull(getField(manager, "resolvedUrl"));
    }

    @Test
    void preContractSidecarWithTextHealthIsAccepted() throws Exception {
        String url = startFakeSidecar(200, "ok");
        when(appsync.graphqlUrl()).thenReturn(Optional.of(url));

        GraphqlSidecarManager manager = manager();

        assertTrue(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
    }

    @Test
    void unhealthySidecarIsNotReadyRatherThanWrong() throws Exception {
        String url = startFakeSidecar(503, "{\"status\":\"starting\",\"contract\":\"2\"}");
        when(appsync.graphqlUrl()).thenReturn(Optional.of(url));

        GraphqlSidecarManager manager = manager();

        assertFalse(manager.isAvailable());
        assertEquals(url, manager.ensureReady());
    }

    private GraphqlSidecarManager manager() {
        return new GraphqlSidecarManager(containerBuilder, lifecycleManager, config, new ObjectMapper());
    }

    private static EmulatorConfig config(EmulatorConfig.AppSyncServiceConfig appsync) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.appsync()).thenReturn(appsync);
        return config;
    }

    private String startFakeSidecar(int status, String healthBody) {
        try {
            fakeSidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
