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
 * {@code threshold} consecutive attempts fail at the transport (an I/O error or an unusable socket,
 * never an HTTP response) it pings the daemon off the caller's thread; only when that ping fails at
 * the transport too does it open, and every Docker call then fails at once with
 * {@link DockerUnavailableException} instead of waiting out its own connect, response and retry
 * timeouts. While open it pings every {@code probeInterval}, and the first answer closes it. A ping
 * is judged by the same rules as a call: any HTTP status is an answer, and a failure that says nothing
 * about the daemon (an interruption, a wait for a pooled connection) neither opens nor closes it. A
 * {@code threshold} of 0 disables it.
 *
 * <p>The confirming ping matters because the breaker sits under the retry layer and so counts
 * attempts, not calls: a burst of broken pipes during a fan-out build is a few failed attempts in a
 * row that the next attempt rides out, and must not take Docker away from every service.
 */
final class DockerCircuitBreaker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DockerCircuitBreaker.class);

    /** What a ping showed about the daemon. */
    enum PingResult {
        /** An HTTP response, whatever its status: the daemon is there. */
        ANSWERED,
        /** A transport failure, as {@link DockerRetry#isDaemonFailure} classifies it. */
        UNREACHABLE,
        /** A failure that says nothing about the daemon, such as an interruption. */
        INCONCLUSIVE
    }

    /** Pings the daemon. */
    @FunctionalInterface
    interface Probe {
        PingResult ping();
    }

    private final int threshold;
    private final Duration probeInterval;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean probeScheduled = new AtomicBoolean();
    private final AtomicInteger probesRun = new AtomicInteger();
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

    /**
     * A ping over {@code transport}, below the breaker, so it reaches the daemon while the breaker is
     * open. The producer gives it a transport of its own, so a full pool never delays it and its short
     * response timeout bounds how long a daemon that accepts a connection but never answers holds it.
     */
    static Probe pingOver(DockerHttpClient transport) {
        return () -> {
            DockerHttpClient.Request ping = DockerHttpClient.Request.builder()
                    .method(DockerHttpClient.Request.Method.GET)
                    .path("/_ping")
                    .build();
            try (DockerHttpClient.Response ignored = transport.execute(ping)) {
                return PingResult.ANSWERED;
            } catch (RuntimeException e) {
                LOG.debugv("Docker daemon ping failed: {0}", e.getMessage());
                return DockerRetry.isDaemonFailure(e) ? PingResult.UNREACHABLE : PingResult.INCONCLUSIVE;
            }
        };
    }

    boolean isEnabled() {
        return threshold > 0;
    }

    boolean isOpen() {
        return open;
    }

    /** Sets how the breaker pings the daemon. */
    void useProbe(Probe newProbe) {
        probe = newProbe;
    }

    /** How many pings have finished, for tests that wait on one. */
    int probesRun() {
        return probesRun.get();
    }

    /** Whether a ping is scheduled or running. */
    boolean isProbeScheduled() {
        return probeScheduled.get();
    }

    /** The exception an open breaker raises in place of a Docker call. */
    DockerUnavailableException unavailable() {
        return new DockerUnavailableException("Docker daemon is unreachable: " + threshold
                + " consecutive Docker call attempts failed at the transport and it has not answered a ping since"
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
        PingResult result;
        try {
            result = probe.ping();
        } catch (RuntimeException e) {
            LOG.debugv("Docker daemon ping failed: {0}", e.getMessage());
            result = DockerRetry.isDaemonFailure(e) ? PingResult.UNREACHABLE : PingResult.INCONCLUSIVE;
        }
        probeScheduled.set(false);
        try {
            switch (result) {
                case ANSWERED -> {
                    consecutiveFailures.set(0);
                    if (open) {
                        closeCircuit();
                    }
                }
                case UNREACHABLE -> {
                    if (!open && !openCircuit()) {
                        return;
                    }
                    scheduleProbe(probeInterval.toMillis());
                }
                case INCONCLUSIVE -> {
                    // Neither opens nor closes: a closed breaker pings again on the next transport failure.
                    if (open) {
                        scheduleProbe(probeInterval.toMillis());
                    }
                }
            }
        } finally {
            probesRun.incrementAndGet();
        }
    }

    /**
     * Opens the breaker unless a call got an answer since the failures that triggered the ping. The
     * open is written before the count is read again, and {@link #recordResponse} resets the count
     * before it reads the open, so a response racing this either shows here as a reset count or sees
     * the open and closes it: it can never leave the breaker open behind it.
     */
    private boolean openCircuit() {
        if (consecutiveFailures.get() < threshold) {
            return false;
        }
        synchronized (schedulerLock) {
            open = true;
        }
        if (consecutiveFailures.get() < threshold) {
            closeCircuit();
            return false;
        }
        LOG.warnv("Docker daemon is unreachable: {0} consecutive Docker call attempts failed at the transport"
                + " and a ping failed too, so Docker calls fail at once until the daemon answers a ping (tried"
                + " every {1} s). Last failure: {2}", threshold, probeInterval.toSeconds(), lastFailure);
        return true;
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
