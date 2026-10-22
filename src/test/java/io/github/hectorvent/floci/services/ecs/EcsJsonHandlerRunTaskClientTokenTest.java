package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.testing.MutableClock;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunTask's {@code clientToken} makes a retried request answer with the tasks the first one started.
 * A retry can arrive while the first request is still launching its containers (an image pull, a
 * {@code dependsOn} wait), so it has to wait for that launch rather than start a second one.
 */
class EcsJsonHandlerRunTaskClientTokenTest {

    private static final String REGION = "us-east-1";
    private static final String FAMILY = "token-family";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retryWhileTheFirstLaunchIsInFlightReturnsTheSameTask() throws Exception {
        EmulatorConfig config = config(false);
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.getExitCodeIfStopped(anyString())).thenReturn(null);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            startEntered.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("container startup was not released");
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-" + task.getTaskArn().hashCode()),
                    Map.of());
        });
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(), null);
        EcsJsonHandler handler = handler(service, config);
        JsonNode request = runTaskRequest("in-flight-token");

        CompletableFuture<Response> first = CompletableFuture.supplyAsync(() -> handler.handle("RunTask", request, REGION));
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<Response> retryResponse = new AtomicReference<>();
            AtomicReference<Throwable> retryFailure = new AtomicReference<>();
            Thread retry = new Thread(() -> {
                try {
                    retryResponse.set(handler.handle("RunTask", request, REGION));
                } catch (Throwable failure) {
                    retryFailure.set(failure);
                }
            });
            retry.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (retry.getState() != Thread.State.WAITING && retry.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(retry.isAlive(), "the retry finished while the first launch was still in flight");
                assertTrue(System.nanoTime() < deadline, "the retry never waited");
                Thread.onSpinWait();
            }

            finishStart.countDown();
            String firstTaskArn = taskArn(first.get(5, TimeUnit.SECONDS));
            retry.join(5000);
            assertFalse(retry.isAlive(), "the retry did not finish");
            assertNull(retryFailure.get());
            assertEquals(firstTaskArn, taskArn(retryResponse.get()));
            verify(containerManager, times(1)).startTask(any(), any(), any(), anyString());
        } finally {
            finishStart.countDown();
            service.stopManagedContainers();
        }
    }

    @Test
    void clientTokenIsForgottenAnHourAfterItsTaskStarted() throws Exception {
        EmulatorConfig config = config(true);
        MutableClock clock = new MutableClock();
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class), config, mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(), null, new EcsExecSessionRegistry(), null, null, clock);
        EcsJsonHandler handler = handler(service, config);
        JsonNode request = runTaskRequest("hour-token");

        String firstTaskArn = taskArn(handler.handle("RunTask", request, REGION));
        clock.advance(Duration.ofMinutes(59));
        assertEquals(firstTaskArn, taskArn(handler.handle("RunTask", request, REGION)));
        clock.advance(Duration.ofMinutes(2));
        assertNotEquals(firstTaskArn, taskArn(handler.handle("RunTask", request, REGION)));
        service.stopManagedContainers();
    }

    @Test
    void resetForgetsClientTokens() throws Exception {
        EmulatorConfig config = config(true);
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class), config, mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(), null);
        EcsJsonHandler handler = handler(service, config);
        JsonNode request = runTaskRequest("reset-token");

        String firstTaskArn = taskArn(handler.handle("RunTask", request, REGION));
        assertEquals(firstTaskArn, taskArn(handler.handle("RunTask", request, REGION)));
        service.clear();
        assertNotEquals(firstTaskArn, taskArn(handler.handle("RunTask", request, REGION)));
        service.stopManagedContainers();
    }

    private static EmulatorConfig config(boolean mockMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        return config;
    }

    private EcsJsonHandler handler(EcsService service, EmulatorConfig config) {
        service.initializeStorage();
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("nginx:alpine");
        service.registerTaskDefinition(FAMILY, List.of(container), null, null, null, null, null, List.of(), REGION);
        return new EcsJsonHandler(service, objectMapper, new HostVolumePolicy(config));
    }

    private JsonNode runTaskRequest(String clientToken) throws Exception {
        return objectMapper.readTree("""
                {"taskDefinition": "%s", "launchType": "FARGATE", "clientToken": "%s"}
                """.formatted(FAMILY, clientToken));
    }

    private String taskArn(Response response) {
        assertEquals(200, response.getStatus());
        JsonNode body = objectMapper.valueToTree(response.getEntity());
        assertEquals(1, body.path("tasks").size(), body.toString());
        return body.path("tasks").get(0).path("taskArn").asText();
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
