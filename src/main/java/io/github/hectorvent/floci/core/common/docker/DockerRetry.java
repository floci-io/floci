package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.io.IOException;

/**
 * Retry policy for docker daemon calls. Every docker command travels over the single shared
 * socket; when many builds drive the daemon at once (e.g. an LZA Bootstrap stage fanning out
 * ~15 CodeBuild actions) it intermittently drops a connection mid-call with a transient I/O
 * error — {@code Broken pipe} or {@code Connection reset} — that docker-java surfaces wrapped
 * in a {@link RuntimeException}. These clear on a retry; a genuine daemon rejection (a 4xx, a
 * name conflict) does not and must surface immediately. Callers wrap idempotent docker calls
 * (create container, copy archive in) so a transient blip does not fail the whole build.
 */
public final class DockerRetry {

    private static final Logger LOG = Logger.getLogger(DockerRetry.class);

    /**
     * Upper bound on any single backoff sleep. Socket saturation from a fan-out build wave can
     * persist for several seconds, so the backoff grows exponentially toward this cap to ride it
     * out rather than exhausting a handful of sub-second retries against a still-busy socket.
     */
    static final long BACKOFF_CAP_MS = 8000L;

    private DockerRetry() {
    }

    /**
     * Exponential backoff for attempt {@code n} (1-based): {@code base * 2^(n-1)}, clamped to
     * {@code cap}. A zero base yields zero (no sleep); the shift is bounded so it cannot overflow.
     */
    static long backoffDelay(int attempt, long baseMillis, long capMillis) {
        if (baseMillis <= 0) {
            return 0L;
        }
        int shift = Math.min(attempt - 1, 30);
        long delay = baseMillis << shift;
        if (delay < 0 || delay > capMillis) {
            return capMillis;
        }
        return delay;
    }

    /** A docker call that returns a value and may throw. */
    @FunctionalInterface
    public interface DockerCall<T> {
        T run() throws Exception;
    }

    /** A docker call that returns nothing and may throw. */
    @FunctionalInterface
    public interface DockerOp {
        void run() throws Exception;
    }

    /**
     * True when {@code t} (or any cause in its chain) is a transient docker I/O failure worth
     * retrying — an {@link IOException} such as {@code Broken pipe} / {@code Connection reset},
     * possibly wrapped by docker-java in a {@link RuntimeException}.
     */
    public static boolean isTransientIo(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IOException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("Broken pipe")
                    || m.toLowerCase().contains("connection reset"))) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * Runs {@code call}, retrying up to {@code maxAttempts} times on a transient docker I/O
     * error ({@link #isTransientIo}) with a fixed backoff between attempts, and returns its
     * value. A non-transient failure is rethrown immediately. A {@link RuntimeException} is
     * rethrown as-is; a checked exception is wrapped.
     */
    public static <T> T call(int maxAttempts, long backoffMillis, DockerCall<T> call) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return call.run();
            } catch (Exception e) {
                if (attempt >= maxAttempts || !isTransientIo(e)) {
                    throw asUnchecked(e);
                }
                LOG.warnv("Transient docker I/O error on attempt {0}/{1}, retrying: {2}",
                        attempt, maxAttempts, e.getMessage());
                sleep(backoffDelay(attempt, backoffMillis, BACKOFF_CAP_MS));
            }
        }
    }

    /** Void variant of {@link #call}. */
    public static void run(int maxAttempts, long backoffMillis, DockerOp op) {
        call(maxAttempts, backoffMillis, () -> {
            op.run();
            return null;
        });
    }

    private static RuntimeException asUnchecked(Exception e) {
        return e instanceof RuntimeException re ? re : new RuntimeException(e);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while backing off before a docker retry", ie);
        }
    }
}
