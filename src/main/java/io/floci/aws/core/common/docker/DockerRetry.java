package io.floci.aws.core.common.docker;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Retry policy for docker daemon calls. Every docker command travels over the single shared
 * socket; when many builds drive the daemon at once (e.g. an LZA Bootstrap stage fanning out
 * ~15 CodeBuild actions) it intermittently drops a connection mid-call with a transient I/O
 * error, {@code Broken pipe} or {@code Connection reset}, that docker-java surfaces wrapped
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
    public static final long BACKOFF_CAP_MS = 8000L;

    private DockerRetry() {
    }

    /**
     * Exponential backoff for attempt {@code n} (1-based): {@code base * 2^(n-1)}, clamped to
     * {@code cap}. A zero base yields zero (no sleep); the shift is bounded so it cannot overflow.
     */
    public static long backoffDelay(int attempt, long baseMillis, long capMillis) {
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

    /**
     * A failure raised on this host while preparing a docker call's payload (for example a tar
     * writer that cannot read its source file), as opposed to one raised by the daemon
     * transport. It is never retried, even when its cause is an {@link IOException}, because
     * rebuilding the payload would fail the same way.
     */
    public static final class LocalFailure extends RuntimeException {
        public LocalFailure(String message, Throwable cause) {
            super(message, cause);
        }
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
     * retrying, an {@link IOException} such as {@code Broken pipe} / {@code Connection reset},
     * possibly wrapped by docker-java in a {@link RuntimeException}. A docker socket file that is
     * missing, or that this process may not open, is never transient.
     */
    public static boolean isTransientIo(Throwable t) {
        // InterruptedIOException covers both a genuine thread interruption (masking it would
        // delay shutdown) and httpclient5's ConnectionRequestTimeoutException, the pool has no
        // free connection lease after waiting the full request timeout, meaning the pool is
        // exhausted rather than a socket blip. Retrying either just re-enters another wait and
        // adds pressure to an already-starved pool, so both must be excluded from the general
        // "any IOException is transient" rule below, checked over the WHOLE chain first, since
        // an outer wrapper IOException would otherwise short-circuit the walk before it ever
        // reaches an inner InterruptedIOException cause. java.net.SocketTimeoutException is ALSO
        // an InterruptedIOException subtype but means something different (the daemon just took
        // too long to answer, not that the pool is exhausted or the thread was interrupted) and
        // must stay retryable, so it's excluded from this exclusion.
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedIOException && !(c instanceof SocketTimeoutException)) {
                return false;
            }
            if (c instanceof LocalFailure || c instanceof DockerUnavailableException) {
                return false;
            }
            if (isUnusableSocket(c)) {
                return false;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IOException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                // Locale.ROOT: the default locale lowercases per the host's language, and a
                // Turkish default turns "I" into a dotless i, so "Broken pipe" stops matching on
                // exactly the hosts least likely to be tested. Matching both messages
                // case-insensitively also covers daemons that vary the capitalisation.
                String lower = m.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe") || lower.contains("connection reset")) {
                    return true;
                }
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * True when {@code t} says the daemon could not be reached at the transport, what
     * {@link DockerCircuitBreaker} counts: a transient I/O failure ({@link #isTransientIo}) or a
     * socket that is missing or may not be opened. A wait for a pooled connection, an interruption, a
     * failure raised on this host ({@link LocalFailure}) and the breaker's own fail-fast exception say
     * nothing about the daemon and do not count.
     */
    static boolean isDaemonFailure(Throwable t) {
        boolean unusableSocket = false;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedIOException && !(c instanceof SocketTimeoutException)) {
                return false;
            }
            if (c instanceof LocalFailure || c instanceof DockerUnavailableException) {
                return false;
            }
            unusableSocket |= isUnusableSocket(c);
            if (c.getCause() == c) {
                break;
            }
        }
        return unusableSocket || isTransientIo(t);
    }

    /**
     * True when {@code t} reports that the docker socket file does not exist ({@code No such
     * file or directory}), as when Floci runs without a mounted docker socket, or that this
     * process may not open it ({@code Permission denied}). A retry cannot create the file or
     * change the process's permissions, so retrying only delays the failure: six attempts held
     * Floci's startup, and each docker call of its shutdown, for about 15 seconds. A Windows named
     * pipe reports the same two cases as {@link NoSuchFileException} and {@link AccessDeniedException},
     * whose message is only the pipe's name.
     */
    private static boolean isUnusableSocket(Throwable t) {
        if (t instanceof NoSuchFileException || t instanceof AccessDeniedException) {
            return true;
        }
        String m = t.getMessage();
        if (m == null) {
            return false;
        }
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.contains("no such file or directory") || lower.contains("permission denied");
    }

    /**
     * Runs {@code call}, retrying up to {@code maxAttempts} times on a transient docker I/O
     * error ({@link #isTransientIo}) with a capped exponential backoff between attempts
     * ({@link #backoffDelay}), and returns its value. A non-transient failure is rethrown
     * immediately. A {@link RuntimeException} is rethrown as-is; a checked exception is wrapped.
     */
    public static <T> T call(int maxAttempts, long backoffMillis, DockerCall<T> call) {
        return call(maxAttempts, backoffMillis, null, call);
    }

    /**
     * As {@link #call(int, long, DockerCall)}, but with one {@code deadline} across every attempt and
     * backoff, counted from the first attempt: no attempt starts, and no backoff is slept, that would
     * begin past it. An attempt that times out at the deadline therefore ends the call, so a silent
     * daemon fails it after about one attempt rather than {@code maxAttempts} of them. An attempt
     * already running is not cut short. A null deadline means none.
     */
    public static <T> T call(int maxAttempts, long backoffMillis, Duration deadline, DockerCall<T> call) {
        return call(maxAttempts, backoffMillis, deadline, System::nanoTime, Thread::sleep, call);
    }

    /** Pause between attempts; tests advance the clock instead of sleeping. */
    @FunctionalInterface
    interface BackoffWait {
        void sleep(long millis) throws InterruptedException;
    }

    /** As {@link #call(int, long, Duration, DockerCall)}, reading time from {@code nanoTime} and pausing with {@code wait}. */
    static <T> T call(int maxAttempts, long backoffMillis, Duration deadline, LongSupplier nanoTime,
                      BackoffWait wait, DockerCall<T> call) {
        if (maxAttempts < 1) {
            // Zero or negative would run the call zero times and fall out of the loop returning
            // nothing, turning a misconfiguration into a silent no-op rather than a failure.
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        long start = nanoTime.getAsLong();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return call.run();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw asUnchecked(e);
                }
                if (attempt >= maxAttempts || !isTransientIo(e)) {
                    throw asUnchecked(e);
                }
                long delay = backoffDelay(attempt, backoffMillis, BACKOFF_CAP_MS);
                if (deadline != null && nanoTime.getAsLong() - start + delay * 1_000_000L >= deadline.toNanos()) {
                    LOG.warnv("Transient docker I/O error on attempt {0}/{1}, not retried: the {2} ms "
                            + "Docker call timeout would pass first: {3}",
                            attempt, maxAttempts, deadline.toMillis(), e.getMessage());
                    throw asUnchecked(e);
                }
                LOG.warnv("Transient docker I/O error on attempt {0}/{1}, retrying: {2}",
                        attempt, maxAttempts, e.getMessage());
                sleep(wait, delay);
                if (deadline != null && nanoTime.getAsLong() - start >= deadline.toNanos()) {
                    // The backoff overslept the deadline: no attempt starts past it.
                    throw asUnchecked(e);
                }
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

    private static void sleep(BackoffWait wait, long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            wait.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while backing off before a docker retry", ie);
        }
    }
}
