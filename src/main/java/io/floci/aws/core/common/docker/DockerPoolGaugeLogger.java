package io.floci.aws.core.common.docker;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Logs how busy the Docker connection pools are, once per interval: for each pool its leased,
 * available and pending connections against its limit and its open long-lived streams by kind and by
 * the service of the container they follow ({@link DockerContainerServices}), then
 * the number of docker-java stream threads. A line is written only while some pool has a lease, a
 * caller waiting for one, or an open stream, so an idle emulator logs nothing. It is the measurement
 * the Docker admission work is sized from (BL-215); there is no metrics endpoint.
 *
 * <p>Owned by {@link DockerClientProducer}, which registers each pool as its client is built and
 * removes it when the client is disposed. The background thread starts with the first pool and
 * stops with the last; an interval of 0 never starts it.
 */
final class DockerPoolGaugeLogger {

    private static final Logger LOG = Logger.getLogger(DockerPoolGaugeLogger.class);

    /** docker-java names the threads that read stream callbacks with this prefix. */
    static final String STREAM_THREAD_PREFIX = "docker-java-stream-";

    private final long intervalSeconds;
    private final Map<String, Supplier<FlociDockerHttpClient.PoolGauges>> pools = new ConcurrentSkipListMap<>();
    private ScheduledExecutorService executor;

    /** @param intervalSeconds seconds between readings, or 0 to never log */
    DockerPoolGaugeLogger(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    synchronized void register(String role, Supplier<FlociDockerHttpClient.PoolGauges> gauges) {
        pools.put(role, gauges);
        if (intervalSeconds > 0 && executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "docker-pool-gauges");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::logSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    synchronized void unregister(String role) {
        pools.remove(role);
        if (pools.isEmpty() && executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void logSafely() {
        try {
            String line = line(readings(), DockerPoolGaugeLogger::countStreamThreads);
            if (line != null) {
                LOG.infov("Docker pools: {0}", line);
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read the Docker pool gauges");
        }
    }

    private Map<String, FlociDockerHttpClient.PoolGauges> readings() {
        Map<String, FlociDockerHttpClient.PoolGauges> readings = new ConcurrentSkipListMap<>();
        pools.forEach((role, gauges) -> readings.put(role, gauges.get()));
        return readings;
    }

    /**
     * The text of one reading, pools in name order, or null when every pool is idle, in which case the
     * stream threads are not counted. For example
     * {@code control-plane leased=2 available=6 pending=0 max=1024; streaming leased=40 available=0
     * pending=0 max=2048 streams[logs=20 wait=20] services[lambda=36 unattributed=4]; stream threads=40}.
     */
    static String line(Map<String, FlociDockerHttpClient.PoolGauges> readings, IntSupplier streamThreads) {
        if (readings.values().stream().allMatch(FlociDockerHttpClient.PoolGauges::idle)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        readings.forEach((role, reading) -> {
            StringBuilder part = new StringBuilder(role)
                    .append(" leased=").append(reading.leased())
                    .append(" available=").append(reading.available())
                    .append(" pending=").append(reading.pending())
                    .append(" max=").append(reading.max());
            if (!reading.openStreams().isEmpty()) {
                List<String> streams = new ArrayList<>();
                reading.openStreams().forEach((kind, open) -> streams.add(kind + "=" + open));
                part.append(" streams[").append(String.join(" ", streams)).append(']');
            }
            if (!reading.openStreamsByService().isEmpty()) {
                List<String> services = new ArrayList<>();
                reading.openStreamsByService().forEach((service, open) -> services.add(service + "=" + open));
                part.append(" services[").append(String.join(" ", services)).append(']');
            }
            parts.add(part.toString());
        });
        parts.add("stream threads=" + streamThreads.getAsInt());
        return String.join("; ", parts);
    }

    /** The live docker-java stream callbacks, each on its own virtual thread. */
    static int countStreamThreads() {
        return FlociInvocationBuilder.activeStreams();
    }
}
