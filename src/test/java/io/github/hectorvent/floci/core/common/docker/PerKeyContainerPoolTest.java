package io.github.hectorvent.floci.core.common.docker;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool.StartedContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Covers the per-key container lifecycle (caching, restart-on-unhealthy, teardown, and the
 * concurrent-first-use guarantee) that every per-repository sidecar manager needs, independent of
 * what kind of container it actually starts.
 */
class PerKeyContainerPoolTest {

    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final PerKeyContainerPool pool = new PerKeyContainerPool(lifecycleManager, "/ping");
    private final List<HttpServer> fakeContainers = new ArrayList<>();

    @AfterEach
    void stopFakeContainers() {
        fakeContainers.forEach(server -> server.stop(0));
    }

    @Test
    void ensureReadyStartsAContainerOnFirstUse() throws IOException {
        String url = startFakePing(200);
        AtomicInteger starterCalls = new AtomicInteger();

        String result = pool.ensureReady("key-1", () -> {
            starterCalls.incrementAndGet();
            return new StartedContainer("container-1", url);
        });

        assertEquals(url, result);
        assertEquals(1, starterCalls.get());
    }

    @Test
    void ensureReadyReturnsTheCachedUrlWithoutCallingTheStarterWhenAlreadyHealthy() throws IOException {
        String url = startFakePing(200);
        pool.ensureReady("key-1", () -> new StartedContainer("container-1", url));

        String result = pool.ensureReady("key-1", () -> {
            throw new AssertionError("starter must not be called for a healthy, already-started container");
        });

        assertEquals(url, result);
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void ensureReadyRestartsAnUnhealthyContainer() throws IOException {
        String staleUrl = startFakePing(200);
        pool.ensureReady("key-1", () -> new StartedContainer("stale-container", staleUrl));
        // Only now does the first container stop being healthy: ensureReady's first call for a
        // key always waits for its just-started container to actually come up, so seeding with a
        // container that was never reachable at all would hang on that wait instead of exercising
        // the restart path this test is for.
        fakeContainers.forEach(server -> server.stop(0));

        String freshUrl = startFakePing(200);
        String result = pool.ensureReady("key-1", () -> new StartedContainer("fresh-container", freshUrl));

        assertEquals(freshUrl, result);
        verify(lifecycleManager).stopAndRemove("stale-container", null);
    }

    @Test
    void stopContainerStopsAndForgetsATrackedContainer() throws IOException {
        String url = startFakePing(200);
        pool.ensureReady("key-1", () -> new StartedContainer("container-1", url));

        pool.stopContainer("key-1");

        verify(lifecycleManager).stopAndRemove("container-1", null);
        // A second ensureReady must start a new container: the old one is genuinely forgotten,
        // not just marked unhealthy.
        AtomicInteger starterCalls = new AtomicInteger();
        pool.ensureReady("key-1", () -> {
            starterCalls.incrementAndGet();
            return new StartedContainer("container-2", url);
        });
        assertEquals(1, starterCalls.get());
    }

    @Test
    void stopContainerIsANoOpForAnUntrackedOrNullKey() {
        pool.stopContainer("never-started");
        pool.stopContainer(null);

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void stopContainerWaitsForAConcurrentFirstUseOfTheSameKeyRatherThanMissingIt() throws Exception {
        String url = startFakePing(200);
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            Future<String> startFuture = pool2.submit(() -> pool.ensureReady("key-1", () -> {
                starterEntered.countDown();
                try {
                    assertTrue(releaseStarter.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StartedContainer("container-1", url);
            }));
            // Once this fires, ensureReady is inside its synchronized block, holding key-1's
            // lock: stopContainer below is guaranteed to block behind it, not race it, with no
            // sleep needed to line the two threads up.
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS));

            Future<?> stopFuture = pool2.submit(() -> pool.stopContainer("key-1"));
            releaseStarter.countDown();

            assertEquals(url, startFuture.get(5, TimeUnit.SECONDS));
            stopFuture.get(5, TimeUnit.SECONDS);
        } finally {
            pool2.shutdown();
            assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));
        }

        // The container was tracked (by ensureReady, before stopContainer could run) and then
        // removed (by stopContainer, once it got the lock): the race does not orphan it.
        verify(lifecycleManager).stopAndRemove("container-1", null);
    }

    @Test
    void stopAllStopsEveryTrackedContainerAndForgetsThem() throws IOException {
        String urlA = startFakePing(200);
        String urlB = startFakePing(200);
        pool.ensureReady("key-a", () -> new StartedContainer("container-a", urlA));
        pool.ensureReady("key-b", () -> new StartedContainer("container-b", urlB));

        pool.stopAll();

        verify(lifecycleManager).stopAndRemove("container-a", null);
        verify(lifecycleManager).stopAndRemove("container-b", null);
        verifyNoMoreInteractions(lifecycleManager);
    }

    @Test
    void stopAllIsANoOpWhenNothingWasEverStarted() {
        pool.stopAll();

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void concurrentFirstUseOfTheSameKeyOnlyStartsOneContainer() throws Exception {
        String url = startFakePing(200);
        AtomicInteger starterCalls = new AtomicInteger();
        int attempts = 8;
        ExecutorService pool2 = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                pool2.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        results.add(pool.ensureReady("shared-key", () -> {
                            starterCalls.incrementAndGet();
                            return new StartedContainer("container-shared", url);
                        }));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool2.shutdown();
            assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(1, starterCalls.get());
        assertEquals(attempts, results.size());
        results.forEach(r -> assertEquals(url, r));
    }

    private String startFakePing(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        fakeContainers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
