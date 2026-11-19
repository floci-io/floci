package io.floci.aws.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;
import com.github.dockerjava.transport.DockerHttpClient.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchBulkheadDockerHttpClientTest {

    private static Request post(String path) {
        return Request.builder().method(Request.Method.POST).path(path).build();
    }

    // Catches: the bulkhead gating calls it was never meant to (stops, exec starts, image creates) or
    // missing a create or start behind an API version prefix.
    @Test
    void onlyContainerCreatesAndStartsAreLaunches() {
        assertTrue(LaunchBulkheadDockerHttpClient.isLaunch(post("/containers/create")));
        assertTrue(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/containers/create?name=floci-x")));
        assertTrue(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/containers/abc123/start")));

        assertFalse(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/containers/abc123/stop")));
        assertFalse(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/exec/abc123/start")));
        assertFalse(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/images/create?fromImage=busybox")));
        assertFalse(LaunchBulkheadDockerHttpClient.isLaunch(post("/v1.47/images/quay.io/containers/create")));
        assertFalse(LaunchBulkheadDockerHttpClient.isLaunch(
                Request.builder().method(Request.Method.GET).path("/v1.47/containers/abc123/json").build()));
    }

    // Catches: a burst of launches reaching the daemon all at once.
    @Test
    void runsAtMostItsSizeOfLaunchesAtOnceAndCompletesThemAll() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        FakeTransport transport = new FakeTransport(request -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new StatusResponse(201);
        });
        LaunchBulkheadDockerHttpClient bulkhead = new LaunchBulkheadDockerHttpClient(transport, 2, Duration.ofSeconds(10));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Response>> launches = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                launches.add(pool.submit(() -> bulkhead.execute(post("/v1.47/containers/create?name=c"))));
            }
            awaitEquals(2, inFlight::get, "two launches should be in flight");
            Thread.sleep(100);
            assertEquals(2, inFlight.get(), "a size-2 bulkhead must hold the other three back");

            release.countDown();
            for (Future<Response> launch : launches) {
                assertEquals(201, launch.get(5, TimeUnit.SECONDS).getStatusCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, maxInFlight.get());
        assertEquals(5, transport.calls.get());
        assertEquals(2, bulkhead.availableSlots(), "every slot comes back once the launches end");
    }

    // Catches: a pull, stop or inspect waiting behind launches that hold every slot.
    @Test
    void otherCallsPassWhileEverySlotIsHeld() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport(request -> {
            if (LaunchBulkheadDockerHttpClient.isLaunch(request)) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new StatusResponse(200);
        });
        LaunchBulkheadDockerHttpClient bulkhead = new LaunchBulkheadDockerHttpClient(transport, 1, Duration.ofSeconds(10));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> launch = pool.submit(() -> bulkhead.execute(post("/containers/abc/start")));
            awaitEquals(0, bulkhead::availableSlots, "the launch should hold the only slot");

            assertEquals(200, bulkhead.execute(post("/v1.47/images/create?fromImage=busybox")).getStatusCode());
            assertEquals(200, bulkhead.execute(post("/v1.47/containers/abc/stop")).getStatusCode());

            release.countDown();
            launch.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // Catches: a launch waiting forever for a slot, or its timeout being replayed by the retry layer.
    @Test
    void aLaunchThatGetsNoSlotInTimeFailsAndIsNotRetried() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport(request -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StatusResponse(201);
        });
        LaunchBulkheadDockerHttpClient bulkhead = new LaunchBulkheadDockerHttpClient(transport, 1, Duration.ofMillis(100));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> holder = pool.submit(() -> bulkhead.execute(post("/containers/create")));
            awaitEquals(0, bulkhead::availableSlots, "the first launch should hold the only slot");

            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> bulkhead.execute(post("/containers/create")));
            assertInstanceOf(LaunchBulkheadDockerHttpClient.LaunchSlotTimeoutException.class, e.getCause());
            assertFalse(DockerRetry.isTransientIo(e), "a slot timeout must not be replayed");

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, transport.calls.get(), "the launch that got no slot never reached the daemon");
    }

    // Catches: the bulkhead above the retry layer, where a launch would keep its slot through every
    // backoff, or the producer leaving it out of the control-plane transport.
    @Test
    void theProducerPutsTheBulkheadUnderTheControlPlaneRetryLayer() {
        AtomicInteger attempts = new AtomicInteger();
        FakeTransport transport = new FakeTransport(request -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException(new SocketException("Broken pipe"));
            }
            return new StatusResponse(201);
        });
        DockerHttpClient control = DockerClientProducer.wrapForRole(transport, "control-plane",
                Duration.ofSeconds(30), 1, Duration.ofMillis(200));

        assertEquals(201, control.execute(post("/v1.47/containers/create?name=retried")).getStatusCode());
        assertEquals(2, attempts.get(), "a named create is retried after a broken pipe");
        assertEquals(201, control.execute(post("/v1.47/containers/abc/start")).getStatusCode(),
                "the retried create must have released its only slot");
    }

    @Test
    void sizeDefaultsToTwiceTheCpusAndAtLeastFour() {
        assertEquals(16, LaunchBulkheadDockerHttpClient.resolveSize(0, 8));
        assertEquals(4, LaunchBulkheadDockerHttpClient.resolveSize(0, 1));
        assertEquals(3, LaunchBulkheadDockerHttpClient.resolveSize(3, 8));
    }

    private static void awaitEquals(int expected, IntSupplier actual, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (actual.getAsInt() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message + ": expected " + expected + " but was " + actual.getAsInt());
            }
            Thread.sleep(5);
        }
    }

    private static final class FakeTransport implements DockerHttpClient {
        private final Function<Request, Response> behaviour;
        final AtomicInteger calls = new AtomicInteger();

        FakeTransport(Function<Request, Response> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Response execute(Request request) {
            calls.incrementAndGet();
            return behaviour.apply(request);
        }

        @Override
        public void close() throws IOException {
            // Nothing to release.
        }
    }

    private record StatusResponse(int status) implements Response {
        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return Map.of();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
