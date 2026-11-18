package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DockerPoolGaugeLoggerTest {

    private static final FlociDockerHttpClient.PoolGauges IDLE =
            new FlociDockerHttpClient.PoolGauges(0, 3, 0, 1024, Map.of());

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
                new TreeMap<>(Map.of("wait", 20, "logs", 20))));
        readings.put("control-plane", IDLE);

        assertEquals("control-plane leased=0 available=3 pending=0 max=1024; "
                        + "streaming leased=40 available=0 pending=0 max=2048 streams[logs=20 wait=20]; "
                        + "stream threads=40",
                DockerPoolGaugeLogger.line(readings, () -> 40));
    }

    // Catches: callers waiting on a full pool reading as idle because no connection is leased yet.
    @Test
    void aCallerWaitingForAConnectionIsNotIdle() {
        FlociDockerHttpClient.PoolGauges waiting = new FlociDockerHttpClient.PoolGauges(0, 0, 2, 1, Map.of());

        assertEquals("control-plane leased=0 available=0 pending=2 max=1; stream threads=0",
                DockerPoolGaugeLogger.line(Map.of("control-plane", waiting), () -> 0));
    }

    @Test
    void streamThreadsAreCountedByTheDockerJavaPrefix() throws InterruptedException {
        int before = DockerPoolGaugeLogger.countStreamThreads();
        Object release = new Object();
        Thread thread = new Thread(() -> {
            synchronized (release) {
                try {
                    release.wait();
                } catch (InterruptedException expected) {
                    // The test interrupts it once counted.
                }
            }
        }, DockerPoolGaugeLogger.STREAM_THREAD_PREFIX + "test");
        thread.setDaemon(true);
        thread.start();
        try {
            assertEquals(before + 1, DockerPoolGaugeLogger.countStreamThreads());
        } finally {
            thread.interrupt();
            thread.join(5_000);
        }
    }
}
