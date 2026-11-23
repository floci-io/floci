package io.floci.aws.services.emrcontainers;

import io.floci.aws.services.emrserverless.SparkJobRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kernels' containers {@link EmrContainersKernelContainers} runs, read from its runner's side. */
class EmrContainersKernelContainersTest {

    private static final String ENDPOINT = "ep0123456789a";
    private static final String KERNEL = "0b9e3c4e-6a1f-4c2d-9b8e-1f2a3b4c5d6e";

    private final Runner runner = new Runner();
    private final EmrContainersKernelContainers kernels = new EmrContainersKernelContainers(runner,
            task -> Thread.ofPlatform().daemon().start(task));

    @Test
    void secretIsTheGatewaysAlone() {
        String secret = kernels.secretFor(ENDPOINT, "000000000000", "us-east-1");

        assertNotNull(kernels.gateway(ENDPOINT, secret));
        assertNull(kernels.gateway(ENDPOINT, secret + "0"));
        assertNull(kernels.gateway(ENDPOINT, null));
        assertNull(kernels.gateway("ep0000000000000", secret));
    }

    @Test
    void launchOnceTheGatewayHasEndedRunsNothing() {
        kernels.secretFor(ENDPOINT, "000000000000", "us-east-1");
        kernels.stopAll(ENDPOINT);

        // As a call whose secret was checked before the gateway ended reaches it after.
        assertFalse(kernels.launch(ENDPOINT, KERNEL, job(kernels.runId(ENDPOINT, KERNEL))));
        assertEquals(List.of(), runner.started);
    }

    @Test
    void launchAgainStopsTheKernelsEarlierContainer() {
        kernels.secretFor(ENDPOINT, "000000000000", "us-east-1");
        String first = kernels.runId(ENDPOINT, KERNEL);
        assertTrue(kernels.launch(ENDPOINT, KERNEL, job(first)));
        awaitStarted(first);

        assertTrue(kernels.launch(ENDPOINT, KERNEL, job(kernels.runId(ENDPOINT, KERNEL))));

        assertEquals(Set.of(first), runner.stopped);
    }

    @Test
    void resetStopsEveryKernelsContainerAndForgetsTheSecrets() {
        String secret = kernels.secretFor(ENDPOINT, "000000000000", "us-east-1");
        String runId = kernels.runId(ENDPOINT, KERNEL);
        assertTrue(kernels.launch(ENDPOINT, KERNEL, job(runId)));
        awaitStarted(runId);

        kernels.reset();

        assertEquals(Set.of(runId), runner.stopped);
        assertNull(kernels.gateway(ENDPOINT, secret));
    }

    private void awaitStarted(String runId) {
        await("run " + runId + " started").atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                .until(() -> runner.started.contains(runId));
    }

    private static SparkJobRunner.Job job(String runId) {
        return new SparkJobRunner.Job(EmrContainersService.SERVICE, runId, "000000000000", "us-east-1",
                "emr-7.5.0-latest", SparkJobRunner.Engine.SPARK, null, null, null, null, Map.of(), List.of(),
                List.of(), Duration.ZERO, null, null, Map.of(), List.of(), null, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), null);
    }

    /** A runner that records the runs it starts, each running until it is stopped, and the runs it stops. */
    private static final class Runner implements SparkJobRunner {
        final List<String> started = new CopyOnWriteArrayList<>();
        final Set<String> stopped = ConcurrentHashMap.newKeySet();
        private final Map<String, CountDownLatch> ends = new ConcurrentHashMap<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            started.add(job.runId());
            try {
                end(job.runId()).await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Outcome.halted();
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
            end(runId).countDown();
        }

        private CountDownLatch end(String runId) {
            return ends.computeIfAbsent(runId, id -> new CountDownLatch(1));
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }
    }
}
