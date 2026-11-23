package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Saturation harness for the Docker transport (BL-706). It starts idle containers the way Lambda
 * does, each followed by a log stream and a container wait on the streaming pool, in steps, and at
 * each step logs what one more container costs: its start latency, the latency of a control-plane
 * ping, the streaming pool's leases and the docker-java stream threads. These are the numbers the
 * admission work (BL-212, BL-216, BL-217) is sized from.
 *
 * <p>It holds stream leases, which no response timeout releases, so it cannot drain itself (that
 * these streams carry no response timeout is covered by {@link FlociDockerHttpClientTest}). The
 * streaming pool is sized for exactly the containers the ramp starts, so the stream of one more waits for a
 * connection and fails with the lease timeout, the failure a saturated Floci reports.
 *
 * <p>Each container carries the {@code io.floci.service} label and is recorded the way
 * {@link ContainerLifecycleManager} records the containers it creates, so the streams it holds are
 * checked to be counted under that service, and its remove to forget it.
 *
 * <p>{@code -Ddocker.saturation.containers=N} raises the ramp (default 40) to measure a host; the
 * three-minute bound fits the default on a hosted runner, so a much larger ramp needs it raised too.
 */
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class DockerPoolSaturationNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(DockerPoolSaturationNightlyIntegrationTest.class);

    private static final String LABEL = "floci.test";
    private static final String LABEL_VALUE = "docker-pool-saturation";
    /** The service the ramp's containers stand in for, as Lambda's log follow and exit wait. */
    private static final String SERVICE = "lambda";
    private static final int CONTAINERS = Integer.getInteger("docker.saturation.containers", 40);
    private static final int STEP = Math.max(1, CONTAINERS / 4);
    /** The streaming pool's response timeout, which its long-lived streams do not carry. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    /** The control plane's, long enough for a create, start or remove on a loaded runner. */
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration LEASE_TIMEOUT = Duration.ofSeconds(2);

    private FlociDockerHttpClient streamingTransport;
    private DockerClient control;
    private DockerClient streaming;
    private boolean dockerAvailable;
    private final List<String> containers = Collections.synchronizedList(new ArrayList<>());
    private final List<Closeable> streams = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void connect() {
        EmulatorConfig config = new SmallRyeConfigBuilder().withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
        String host = DockerClientProducer.resolveDockerConnection(config.docker(), System::getenv).host();
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(host).build();
        control = DockerClientImpl.getInstance(clientConfig, DockerClientProducer.wrapForRole(
                DockerClientProducer.newHttpClient(clientConfig, 16, CONTROL_TIMEOUT, LEASE_TIMEOUT), "control-plane",
                CONTROL_TIMEOUT));
        // Two streams per container, the log follow and the wait, and not one connection more.
        streamingTransport = DockerClientProducer.newHttpClient(clientConfig, 2 * CONTAINERS, RESPONSE_TIMEOUT,
                LEASE_TIMEOUT);
        streaming = DockerClientProducer.newDockerClient(clientConfig,
                DockerClientProducer.wrapForRole(streamingTransport, "streaming", RESPONSE_TIMEOUT));
        dockerAvailable = DockerAvailability.answers(control);
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon is unavailable");
        removeLeftovers();
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            synchronized (streams) {
                for (Closeable stream : streams) {
                    closeQuietly(stream);
                }
            }
            if (control != null && dockerAvailable) {
                synchronized (containers) {
                    for (String id : containers) {
                        removeQuietly(id);
                    }
                }
                removeLeftovers();
            }
        } finally {
            // A cleanup that throws still leaves no connection pool open for the rest of the run.
            try {
                if (control != null) {
                    control.close();
                }
            } finally {
                if (streaming != null) {
                    streaming.close();
                }
            }
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aFullStreamingPoolMakesTheNextStreamWaitThenFailOnTheLeaseTimeout() throws Exception {
        try (PullImageResultCallback pulled = new PullImageResultCallback()) {
            assertTrue(control.pullImageCmd(TestImages.BUSYBOX).exec(pulled).awaitCompletion(120, TimeUnit.SECONDS),
                    "pulling " + TestImages.BUSYBOX);
        }

        List<String> report = new ArrayList<>();
        report.add("containers  start-ms(median)  ping-ms(median)  streaming-leased  pending  stream-threads");
        for (int started = 0; started < CONTAINERS; ) {
            int target = Math.min(CONTAINERS, started + STEP);
            List<Long> startMillis = new ArrayList<>();
            for (; started < target; started++) {
                String id = createIdleContainer();
                long begin = System.nanoTime();
                control.startContainerCmd(id).exec();
                startMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                followLikeLambda(id);
            }
            int expected = 2 * target;
            FlociDockerHttpClient.PoolGauges reading = awaitGauges(gauges -> gauges.leased() == expected,
                    "the streaming pool to hold " + expected + " streams");
            report.add(String.format("%10d  %16d  %15d  %16d  %7d  %14d", target, median(startMillis),
                    median(pingMillis()), reading.leased(), reading.pending(),
                    DockerPoolGaugeLogger.countStreamThreads()));
        }
        LOG.infov("Docker pool saturation, {0} containers in steps of {1}:\n{2}", CONTAINERS, STEP,
                String.join("\n", report));

        FlociDockerHttpClient.PoolGauges quiet = streamingTransport.gauges();
        assertEquals(2 * CONTAINERS, quiet.leased(), "every container should hold both of its streams: " + quiet);
        assertEquals(Map.of("logs", CONTAINERS, "wait", CONTAINERS), quiet.openStreams());
        // Each stream is read on a virtual thread of its own, counted while its callback runs.
        int streamThreads = DockerPoolGaugeLogger.countStreamThreads();
        assertTrue(streamThreads >= 2 * CONTAINERS, "one docker-java stream thread per open stream, counted "
                + streamThreads);
        assertEquals(Map.of(SERVICE, 2 * CONTAINERS), quiet.openStreamsByService(),
                "every stream should be counted under the service of the container it follows");

        // The pool is full: one more container's log stream waits for a connection, then fails.
        String extra = createIdleContainer();
        control.startContainerCmd(extra).exec();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        long begin = System.nanoTime();
        streams.add(followLogs(extra, new ResultCallback.Adapter<Frame>() {
            @Override
            public void onError(Throwable throwable) {
                failure.complete(throwable);
            }

            @Override
            public void onComplete() {
                failure.complete(null);
            }
        }));
        FlociDockerHttpClient.PoolGauges waiting = awaitGauges(gauges -> gauges.pending() >= 1,
                "the extra stream to wait for a connection");
        assertEquals(2 * CONTAINERS, waiting.leased(), waiting.toString());

        Throwable error = failure.get(LEASE_TIMEOUT.multipliedBy(10).toMillis(), TimeUnit.MILLISECONDS);
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue(error != null, "the extra stream should fail, not complete");
        assertInstanceOf(ConnectionRequestTimeoutException.class, rootCause(error), "got " + error);
        assertTrue(waitedMillis >= LEASE_TIMEOUT.toMillis() - 200,
                "should fail after the " + LEASE_TIMEOUT.toMillis() + " ms lease timeout, took " + waitedMillis + " ms");
        LOG.infov("With {0} streams open the next one waited {1} ms and failed with {2}", 2 * CONTAINERS,
                waitedMillis, rootCause(error).getClass().getSimpleName());

        // A remove that succeeds forgets the container, whichever transport sent it.
        assertEquals(SERVICE, DockerContainerServices.serviceOf(extra));
        control.removeContainerCmd(extra).withForce(true).exec();
        assertEquals(DockerContainerServices.UNATTRIBUTED, DockerContainerServices.serviceOf(extra));
    }

    private String createIdleContainer() {
        Map<String, String> labels = Map.of(LABEL, LABEL_VALUE, ContainerStorageHelper.SERVICE_LABEL, SERVICE);
        String id = control.createContainerCmd(TestImages.BUSYBOX)
                .withCmd("sleep", "600")
                .withLabels(labels)
                .exec().getId();
        DockerContainerServices.record(id, null, labels);
        containers.add(id);
        return id;
    }

    /** The two streams a Lambda container holds on the streaming pool: its log follow and its exit wait. */
    private void followLikeLambda(String id) {
        streams.add(followLogs(id, new ResultCallback.Adapter<Frame>() {
            @Override
            public void onError(Throwable ignored) {
                // Expected once the test closes the stream.
            }
        }));
        streams.add(streaming.waitContainerCmd(id).exec(new ResultCallback.Adapter<WaitResponse>() {
            @Override
            public void onError(Throwable ignored) {
                // Expected once the test closes the stream.
            }
        }));
    }

    /** Follows a container's stdout and stderr on the streaming pool, as Lambda's log follow does. */
    private <T extends ResultCallback<Frame>> T followLogs(String id, T callback) {
        return streaming.logContainerCmd(id).withStdOut(true).withStdErr(true).withFollowStream(true).exec(callback);
    }

    private List<Long> pingMillis() {
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long begin = System.nanoTime();
            control.pingCmd().exec();
            millis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
        }
        return millis;
    }

    private FlociDockerHttpClient.PoolGauges awaitGauges(Predicate<FlociDockerHttpClient.PoolGauges> condition,
                                                         String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        FlociDockerHttpClient.PoolGauges reading = streamingTransport.gauges();
        while (!condition.test(reading)) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what + ": " + reading);
            }
            Thread.sleep(50);
            reading = streamingTransport.gauges();
        }
        return reading;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void removeLeftovers() {
        for (Container container : control.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(LABEL, LABEL_VALUE)).exec()) {
            removeQuietly(container.getId());
        }
    }

    private void removeQuietly(String id) {
        try {
            control.removeContainerCmd(id).withForce(true).exec();
        } catch (NotFoundException expected) {
            LOG.debugv("Container {0} was already removed", id);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Container {0} could not be removed; it may be left running", id);
        }
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.debugv(e, "Closing a stream at teardown");
        }
    }
}
