package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Admits physical Lambda execution environments independently from Lambda's logical
 * concurrency limiter.
 *
 * <p>The limiter is disabled when {@code max-physical-environments} is absent. In that
 * mode every acquisition still has a close-once permit and contributes to status, but
 * no caller waits and the existing AWS-like emulator behavior is unchanged.
 *
 * <p>When enabled, waiters are admitted in FIFO order. A waiter has one bounded,
 * interruptible wait; timed-out or interrupted waiters are removed before returning so
 * a later release cannot accidentally grant a permit to a cancelled invocation.
 */
@ApplicationScoped
public class LambdaEnvironmentLimiter implements AutoCloseable {

    /** A non-positive configured value means that the physical cap is disabled. */
    private static final int UNBOUNDED = 0;
    private static final int DEFAULT_WAIT_SECONDS = 15;

    private final int maxPhysicalEnvironments;
    private final long waitNanos;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private final Map<String, Integer> inFlightByKey = new HashMap<>();
    private final Map<String, Integer> queuedByKey = new HashMap<>();
    private long granted;
    private long timedOut;
    private long interrupted;
    private int inFlight;
    private boolean closed;

    private static final class Waiter {
        private final String key;

        private Waiter(String key) {
            this.key = key;
        }
    }

    @Inject
    public LambdaEnvironmentLimiter(EmulatorConfig config) {
        this(configuredLimit(config), configuredWaitSeconds(config));
    }

    /** Test-only constructor. A non-positive limit disables the physical cap. */
    LambdaEnvironmentLimiter(int maxPhysicalEnvironments, int waitSeconds) {
        this.maxPhysicalEnvironments = Math.max(UNBOUNDED, maxPhysicalEnvironments);
        this.waitNanos = TimeUnit.SECONDS.toNanos(Math.max(0, waitSeconds));
    }

    /** Test-only constructor for the unchanged, unbounded default behavior. */
    LambdaEnvironmentLimiter() {
        this(UNBOUNDED, DEFAULT_WAIT_SECONDS);
    }

    /**
     * Acquires a physical execution-environment slot for {@code environmentKey}.
     *
     * <p>The public method preserves the existing no-checked-exception shape used by
     * Lambda services. If an invocation thread is interrupted while waiting, the
     * interrupt flag is restored and an {@link AdmissionInterruptedException} is
     * thrown so the caller can return a bounded invocation failure.
     */
    public Permit acquire(String environmentKey) {
        // Keep the opt-in nature of this limiter observable: without a physical cap there is
        // no wait to interrupt, so retain the historical non-blocking acquisition semantics
        // even if a caller arrives with a stale interrupt flag.
        if (!bounded()) {
            String key = normalizeKey(environmentKey);
            lock.lock();
            try {
                ensureOpen(key);
                return grant(key);
            } finally {
                lock.unlock();
            }
        }
        try {
            return acquireInterruptibly(environmentKey);
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdmissionInterruptedException(normalizeKey(environmentKey), e);
        }
    }

    /**
     * Interruptible form used by tests and by callers that already expose checked
     * interruption.
     */
    public Permit acquireInterruptibly(String environmentKey) throws java.lang.InterruptedException {
        String key = normalizeKey(environmentKey);
        lock.lockInterruptibly();
        try {
            ensureOpen(key);
            if (!bounded() && waiters.isEmpty()) {
                return grant(key);
            }

            Waiter waiter = new Waiter(key);
            waiters.addLast(waiter);
            increment(queuedByKey, key);
            long startedAt = System.nanoTime();
            while (true) {
                if (waiter == waiters.peekFirst() && (!bounded() || inFlight < maxPhysicalEnvironments)) {
                    waiters.removeFirst();
                    decrement(queuedByKey, key);
                    return grant(key);
                }

                long remaining = waitNanos - (System.nanoTime() - startedAt);
                if (remaining <= 0) {
                    removeWaiter(waiter);
                    timedOut++;
                    changed.signalAll();
                    throw new AdmissionTimeoutException(key, maxPhysicalEnvironments, waitNanos);
                }
                try {
                    changed.awaitNanos(remaining);
                } catch (java.lang.InterruptedException e) {
                    removeWaiter(waiter);
                    interrupted++;
                    changed.signalAll();
                    throw e;
                }
                try {
                    ensureOpen(key);
                } catch (RuntimeException e) {
                    removeWaiter(waiter);
                    changed.signalAll();
                    throw e;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private Permit grant(String key) {
        inFlight++;
        increment(inFlightByKey, key);
        granted++;
        return new PermitImpl(this, key);
    }

    private void release(String key) {
        lock.lock();
        try {
            // A close-once Permit should make this branch impossible. Keep it
            // defensive so a malformed caller cannot make status negative.
            if (inFlight <= 0) {
                return;
            }
            inFlight--;
            decrement(inFlightByKey, key);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ensureOpen(String key) {
        if (closed) {
            throw new AdmissionClosedException(key);
        }
    }

    private void removeWaiter(Waiter waiter) {
        if (waiters.remove(waiter)) {
            decrement(queuedByKey, waiter.key);
        }
    }

    /**
     * Returns a point-in-time readback of physical admission. The returned object is
     * immutable and safe to log or expose to a status endpoint.
     */
    public Status status() {
        lock.lock();
        try {
            return new Status(maxPhysicalEnvironments, inFlight, waiters.size(),
                    availableUnsafe(), granted, timedOut, interrupted, closed);
        } finally {
            lock.unlock();
        }
    }

    /** Returns status scoped to one account/function/version identity. */
    public KeyStatus status(String environmentKey) {
        String key = normalizeKey(environmentKey);
        lock.lock();
        try {
            return new KeyStatus(key, inFlightByKey.getOrDefault(key, 0), queuedByKey.getOrDefault(key, 0));
        } finally {
            lock.unlock();
        }
    }

    public int configuredLimit() {
        return maxPhysicalEnvironments;
    }

    public int inFlightCount() {
        lock.lock();
        try {
            return inFlight;
        } finally {
            lock.unlock();
        }
    }

    public int queuedCount() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean bounded() {
        return maxPhysicalEnvironments > UNBOUNDED;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private int availableUnsafe() {
        return bounded() ? Math.max(0, maxPhysicalEnvironments - inFlight) : Integer.MAX_VALUE;
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? "unknown" : key;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static void decrement(Map<String, Integer> counts, String key) {
        counts.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private static int configuredLimit(EmulatorConfig config) {
        if (config == null || config.services() == null || config.services().lambda() == null) {
            return UNBOUNDED;
        }
        OptionalInt configured = config.services().lambda().maxPhysicalEnvironments();
        return configured == null || configured.isEmpty() ? UNBOUNDED : configured.getAsInt();
    }

    private static int configuredWaitSeconds(EmulatorConfig config) {
        if (config == null || config.services() == null || config.services().lambda() == null) {
            return DEFAULT_WAIT_SECONDS;
        }
        return Math.max(0, config.services().lambda().physicalEnvironmentWaitTimeoutSeconds());
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class PermitImpl implements Permit {
        private final LambdaEnvironmentLimiter owner;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PermitImpl(LambdaEnvironmentLimiter owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(key);
            }
        }
    }

    public record Status(int configuredLimit,
                         int inFlight,
                         int queued,
                         int available,
                         long granted,
                         long timedOut,
                         long interrupted,
                         boolean closed) {
        public boolean bounded() {
            return configuredLimit > UNBOUNDED;
        }
    }

    public record KeyStatus(String environmentKey, int inFlight, int queued) {
    }

    public static class AdmissionTimeoutException extends RuntimeException {
        private final String environmentKey;
        private final int configuredLimit;

        public AdmissionTimeoutException(String environmentKey, int configuredLimit, long waitNanos) {
            super("Timed out waiting for a Lambda execution environment for " + environmentKey
                    + " after " + TimeUnit.NANOSECONDS.toMillis(waitNanos) + "ms"
                    + " (configured limit=" + configuredLimit + ")");
            this.environmentKey = environmentKey;
            this.configuredLimit = configuredLimit;
        }

        public String environmentKey() {
            return environmentKey;
        }

        public int configuredLimit() {
            return configuredLimit;
        }
    }

    public static class AdmissionInterruptedException extends RuntimeException {
        private final String environmentKey;

        public AdmissionInterruptedException(String environmentKey, Throwable cause) {
            super("Interrupted waiting for a Lambda execution environment for " + environmentKey, cause);
            this.environmentKey = environmentKey;
        }

        public String environmentKey() {
            return environmentKey;
        }
    }

    public static class AdmissionClosedException extends RuntimeException {
        private final String environmentKey;

        public AdmissionClosedException(String environmentKey) {
            super("Lambda execution-environment admission is closed for " + environmentKey);
            this.environmentKey = environmentKey;
        }

        public String environmentKey() {
            return environmentKey;
        }
    }
}
