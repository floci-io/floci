package io.floci.aws.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One view of whether the Docker daemon is reachable, shared by both Docker clients. After
 * {@code threshold} consecutive transport failures (an I/O error or an unusable socket, never an
 * HTTP response) it pings the daemon off the caller's thread; only when that ping fails too does it
 * open, and every Docker call then fails at once with {@link DockerUnavailableException} instead of
 * waiting out its own connect, response and retry timeouts. While open it pings every
 * {@code probeInterval}, and the first answer closes it. A {@code threshold} of 0 disables it.
 *
 * <p>The confirming ping matters because the breaker sits under the retry layer and so counts
 * attempts, not calls: a burst of broken pipes during a fan-out build is a few failed attempts in a
 * row that the next attempt rides out, and must not take Docker away from every service.
 */
final class DockerCircuitBreaker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DockerCircuitBreaker.class);

    /** Whether the daemon answered a ping. */
    @FunctionalInterface
    interface Probe {
        boolean daemonAnswers();
    }

    private final int threshold;
    private final Duration probeInterval;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean probeScheduled = new AtomicBoolean();
    private final Object schedulerLock = new Object();
    private volatile boolean open;
    private volatile String lastFailure = "";
    private volatile Probe probe;
    private ScheduledExecutorService scheduler;
    private boolean closed;

    DockerCircuitBreaker(int threshold, Duration probeInterval) {
        this.threshold = threshold;
        this.probeInterval = probeInterval;
    }

    /** A ping over {@code transport}, below the breaker, so it reaches the daemon while the breaker is open. */
    static Probe pingOver(DockerHttpClient transport) {
        return () -> {
            DockerHttpClient.Request ping = DockerHttpClient.Request.builder()
                    .method(DockerHttpClient.Request.Method.GET)
                    .path("/_ping")
                    .build();
            try (DockerHttpClient.Response response = transport.execute(ping)) {
                return response.getStatusCode() == 200;
            } catch (RuntimeException e) {
                LOG.debugv("Docker daemon ping failed: {0}", e.getMessage());
                return false;
            }
        };
    }

    boolean isEnabled() {
        return threshold > 0;
    }

    boolean isOpen() {
        return open;
    }

    /** Sets how the breaker pings the daemon; the first probe set wins unless {@code replace}. */
    void useProbe(Probe newProbe, boolean replace) {
        if (replace || probe == null) {
            probe = newProbe;
        }
    }

    /** The exception an open breaker raises in place of a Docker call. */
    DockerUnavailableException unavailable() {
        return new DockerUnavailableException("Docker daemon is unreachable: " + threshold
                + " consecutive Docker calls failed at the transport and it has not answered a ping since"
                + " (last failure: " + lastFailure + ")");
    }

    /** A Docker call got an HTTP response, whatever its status: the daemon is there. */
    void recordResponse() {
        consecutiveFailures.set(0);
        if (open) {
            closeCircuit();
        }
    }

    /** A Docker call failed at the transport, as {@link DockerRetry#isDaemonFailure} classifies it. */
    void recordFailure(Throwable failure) {
        if (!isEnabled()) {
            return;
        }
        lastFailure = String.valueOf(failure.getMessage());
        if (consecutiveFailures.incrementAndGet() >= threshold && !open) {
            scheduleProbe(0);
        }
    }

    private void scheduleProbe(long delayMillis) {
        if (probe == null || !probeScheduled.compareAndSet(false, true)) {
            return;
        }
        synchronized (schedulerLock) {
            if (closed) {
                probeScheduled.set(false);
                return;
            }
            if (scheduler == null) {
                ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "floci-docker-breaker-probe");
                    thread.setDaemon(true);
                    return thread;
                });
                executor.setRemoveOnCancelPolicy(true);
                scheduler = executor;
            }
            scheduler.schedule(this::runProbe, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void runProbe() {
        boolean answers;
        try {
            answers = probe.daemonAnswers();
        } catch (RuntimeException e) {
            LOG.debugv("Docker daemon ping failed: {0}", e.getMessage());
            answers = false;
        }
        probeScheduled.set(false);
        if (answers) {
            consecutiveFailures.set(0);
            if (open) {
                closeCircuit();
            }
            return;
        }
        if (!open) {
            if (consecutiveFailures.get() < threshold) {
                // A call got an answer while the ping was failing: the daemon is back.
                return;
            }
            open = true;
            LOG.warnv("Docker daemon is unreachable: {0} consecutive Docker calls failed at the transport and"
                    + " a ping failed too, so Docker calls fail at once until the daemon answers a ping (tried"
                    + " every {1} s). Last failure: {2}", threshold, probeInterval.toSeconds(), lastFailure);
        }
        scheduleProbe(probeInterval.toMillis());
    }

    private void closeCircuit() {
        synchronized (schedulerLock) {
            if (!open) {
                return;
            }
            open = false;
        }
        LOG.info("Docker daemon answered again; Docker calls go through");
    }

    @Override
    public void close() {
        synchronized (schedulerLock) {
            closed = true;
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }
}
