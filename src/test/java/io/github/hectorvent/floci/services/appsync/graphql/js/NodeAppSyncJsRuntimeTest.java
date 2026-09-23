package io.github.hectorvent.floci.services.appsync.graphql.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pre-configured URL path: a server someone else is already running, with no container
 * management at all. Same contract as {@code floci.services.duck.url}.
 */
class NodeAppSyncJsRuntimeTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.JsRuntimeConfig jsRuntime = mock(EmulatorConfig.JsRuntimeConfig.class);

    private HttpServer server;
    private final List<String> requestedPaths = new ArrayList<>();
    private NodeAppSyncJsRuntime runtime;
    private volatile boolean failEvaluate;

    @BeforeEach
    void startStubRuntime() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            if (failEvaluate && "/evaluate".equals(exchange.getRequestURI().getPath())) {
                // Dropped without a response, so the client sees a transport failure. A 500 would
                // not do: post() rethrows an AwsException before reaching the path under test.
                exchange.close();
                return;
            }
            byte[] body = ("/health".equals(exchange.getRequestURI().getPath())
                    ? "{\"ok\":true,\"runtime\":\"stub\"}"
                    : "{\"ok\":true,\"result\":\"from-the-stub\",\"stash\":{},\"earlyReturn\":false,\"errors\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AppSyncServiceConfig appsync = mock(EmulatorConfig.AppSyncServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.appsync()).thenReturn(appsync);
        when(appsync.jsRuntime()).thenReturn(jsRuntime);
        when(jsRuntime.enabled()).thenReturn(true);
        when(jsRuntime.enforceAppsyncSubset()).thenReturn(true);
        when(jsRuntime.evaluationTimeoutSeconds()).thenReturn(5);
        when(jsRuntime.url()).thenReturn(
                Optional.of("http://127.0.0.1:" + server.getAddress().getPort()));

        runtime = new NodeAppSyncJsRuntime(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, mock(RegionResolver.class), new ObjectMapper());
    }

    @AfterEach
    void stopStubRuntime() {
        server.stop(0);
    }

    @Test
    void aPreConfiguredUrlIsUsedWithoutTouchingDocker() {
        JsEvaluation evaluation = runtime.evaluate("export function request() { return {}; }",
                "request", Map.of("arguments", Map.of()));

        assertEquals("from-the-stub", evaluation.result());
        // The point of the knob: no image pull, no container created, adopted or inspected.
        verifyNoInteractions(containerBuilder);
        verifyNoInteractions(lifecycleManager);
        verifyNoInteractions(logStreamer);
        assertTrue(requestedPaths.contains("/health"), "the configured URL is probed before use");
        assertTrue(requestedPaths.contains("/evaluate"), "the evaluation goes to the configured URL");
    }


    /**
     * The window the concurrency fix closes: a failing evaluation used to clear containerId without
     * stopping the container, so the only handle to a live sidecar was dropped and teardown could
     * not reach it. Racing failures against startup, every container this runtime creates must also
     * be stopped.
     */
    @Test
    void aFailedEvaluationRacingStartupNeverLeavesAContainerBehind() throws Exception {
        when(jsRuntime.url()).thenReturn(Optional.empty());
        when(jsRuntime.image()).thenReturn("node:22-alpine");
        when(jsRuntime.containerName()).thenReturn("appsync-js-runtime-test");
        when(jsRuntime.port()).thenReturn(0);
        when(jsRuntime.startTimeoutSeconds()).thenReturn(5);
        when(jsRuntime.keepRunningOnShutdown()).thenReturn(false);
        when(jsRuntime.dockerNetwork()).thenReturn(Optional.empty());
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());

        // A fluent builder that yields a spec, and a lifecycle manager that hands out a fresh
        // container each time, pointed at the stub whose /evaluate always fails.
        ContainerBuilder.Builder builder =
                mock(ContainerBuilder.Builder.class, withSettings().defaultAnswer(org.mockito.Answers.RETURNS_SELF));
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        Set<String> created = ConcurrentHashMap.newKeySet();
        Set<String> stopped = ConcurrentHashMap.newKeySet();
        AtomicInteger counter = new AtomicInteger();
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            String id = "container-" + counter.incrementAndGet();
            created.add(id);
            return new ContainerInfo(id, Map.of(4600,
                    new EndpointInfo("127.0.0.1", server.getAddress().getPort())));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            stopped.add(invocation.getArgument(0));
            return null;
        }).when(lifecycleManager).stopAndRemove(anyString(), any());

        failEvaluate = true;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    runtime.evaluate("export function request() { return {}; }", "request", Map.of());
                } catch (Exception expected) {
                    // every evaluation fails; the point is what happens to the container
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the racing evaluations did not finish");
        pool.shutdownNow();

        runtime.stopManagedContainers();

        assertTrue(created.size() > 0, "the race never started a container, so it proves nothing");
        assertEquals(created, stopped,
                "every sidecar this runtime created must also be stopped; one left in `created` "
                        + "only is a container leaked by a failed evaluation clearing its id");
    }

    @Test
    void stoppingManagedContainersLeavesAPreConfiguredServerAlone() {
        runtime.evaluate("export function request() { return {}; }", "request", Map.of());

        runtime.stopManagedContainers();

        // Not ours to stop: nothing was started, so nothing is removed.
        verifyNoInteractions(lifecycleManager);
    }
}
