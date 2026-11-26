package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(ParallelLocks.DOCKER_ACTIVE_STREAMS)
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class DockerPoolGaugeLoggerTest {

    private static final FlociDockerHttpClient.PoolGauges IDLE =
            new FlociDockerHttpClient.PoolGauges(0, 3, 0, 1024, Map.of(), Map.of());

    // Catches: an idle emulator writing a pool line every interval, or paying for a thread scan on a
    // reading that writes nothing.
    @Test
    void noLineAndNoThreadCountWhileEveryPoolIsIdle() {
        assertNull(DockerPoolGaugeLogger.line(Map.of("control-plane", IDLE, "streaming", IDLE), () -> {
            throw new AssertionError("stream threads counted for an idle reading");
        }));
    }

    @Test
    void aBusyPoolReportsEveryPoolInNameOrderWithItsStreamsAndTheStreamThreads() {
        Map<String, FlociDockerHttpClient.PoolGauges> readings = new TreeMap<>();
        readings.put("streaming", new FlociDockerHttpClient.PoolGauges(40, 0, 0, 2048,
                new TreeMap<>(Map.of("wait", 20, "logs", 20)),
                new TreeMap<>(Map.of("lambda", 36, "unattributed", 4))));
        readings.put("control-plane", IDLE);

        assertEquals("control-plane leased=0 available=3 pending=0 max=1024; "
                        + "streaming leased=40 available=0 pending=0 max=2048 streams[logs=20 wait=20] "
                        + "services[lambda=36 unattributed=4]; "
                        + "stream threads=40",
                DockerPoolGaugeLogger.line(readings, () -> 40));
    }

    // Catches: callers waiting on a full pool reading as idle because no connection is leased yet.
    @Test
    void aCallerWaitingForAConnectionIsNotIdle() {
        FlociDockerHttpClient.PoolGauges waiting =
                new FlociDockerHttpClient.PoolGauges(0, 0, 2, 1, Map.of(), Map.of());

        assertEquals("control-plane leased=0 available=0 pending=2 max=1; stream threads=0",
                DockerPoolGaugeLogger.line(Map.of("control-plane", waiting), () -> 0));
    }

    // Catches: a docker-java stream read on a platform thread of its own again, or the gauge losing count
    // of the virtual ones, which no thread-group listing sees.
    @Test
    void aStreamCallbackRunsOnAVirtualThreadAndIsCountedWhileItRuns() throws Exception {
        CountDownLatch inCallback = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        byte[] frame = {1, 0, 0, 0, 0, 0, 0, 5, 'h', 'e', 'l', 'l', 'o'};
        DockerHttpClient transport = new DockerHttpClient() {
            @Override
            public Response execute(Request request) {
                return new Response() {
                    @Override
                    public int getStatusCode() {
                        return 200;
                    }

                    @Override
                    public Map<String, List<String>> getHeaders() {
                        return Map.of();
                    }

                    @Override
                    public InputStream getBody() {
                        return new ByteArrayInputStream(frame);
                    }

                    @Override
                    public void close() {
                        // Nothing to release.
                    }
                };
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://127.0.0.1:2375").build();
        // Earlier clients can close before their callbacks retire; the lock prevents new producers.
        awaitStreamCount(0, "earlier stream callbacks should retire");
        int before = DockerPoolGaugeLogger.countStreamThreads();

        try (DockerClient client = DockerClientProducer.newDockerClient(config, transport);
             ResultCallback.Adapter<Frame> callback = client.logContainerCmd("c1").withStdOut(true)
                     .withFollowStream(true).exec(new ResultCallback.Adapter<>() {
                         @Override
                         public void onNext(Frame received) {
                             virtual.set(Thread.currentThread().isVirtual());
                             inCallback.countDown();
                             try {
                                 release.await(5, TimeUnit.SECONDS);
                             } catch (InterruptedException e) {
                                 Thread.currentThread().interrupt();
                             }
                         }
                     })) {
            assertTrue(inCallback.await(5, TimeUnit.SECONDS), "the frame should reach the callback");
            assertTrue(virtual.get(), "the stream callback should run on a virtual thread");
            assertEquals(before + 1, DockerPoolGaugeLogger.countStreamThreads());

            release.countDown();
            assertTrue(callback.awaitCompletion(5, TimeUnit.SECONDS));
        }
        awaitStreamCount(before, "an ended stream is no longer counted");
    }

    private static void awaitStreamCount(int expected, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (DockerPoolGaugeLogger.countStreamThreads() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, DockerPoolGaugeLogger.countStreamThreads(), message);
    }
}
