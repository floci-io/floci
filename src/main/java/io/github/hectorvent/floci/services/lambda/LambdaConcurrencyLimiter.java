package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces Lambda concurrency limits at invocation time.
 *
 * <p>AWS Lambda concurrency is scoped to an account <b>per region</b>; a
 * function's reserved value does not compete with functions in other regions.
 * Accordingly this limiter partitions its state by region (extracted from the
 * function ARN), and the configured {@code regionLimit}/{@code unreservedMin}
 * apply independently to each region.
 *
 * <p>Two layers of enforcement:
 * <ul>
 *   <li><b>Reserved (per-function)</b>: when a function has a reserved value,
 *       inflight invocations are counted against that value and do not consume
 *       the region's unreserved pool.</li>
 *   <li><b>Unreserved (region-shared)</b>: functions without a reserved value
 *       share {@code regionLimit - Σreserved} permits within their region.</li>
 * </ul>
 */
@ApplicationScoped
public class LambdaConcurrencyLimiter {

    private static final Logger LOG = Logger.getLogger(LambdaConcurrencyLimiter.class);
    /** Tracks malformed ARNs already logged so the warn fires once per unique input. */
    private static final Set<String> LOGGED_MALFORMED_ARNS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Inflight counts per function ARN (globally unique). Entries are
     * retained even when the count drops to zero — see {@link #reset} for
     * the race this avoids. The map therefore grows by one entry per
     * distinct ARN the limiter has ever seen, including entries left over
     * from functions that have been deleted and recreated under a new
     * name. For a local emulator the resulting footprint is negligible.
     */
    private final ConcurrentHashMap<String, AtomicInteger> inflight = new ConcurrentHashMap<>();
    /** Reserved values partitioned by region. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> reservedByRegion
            = new ConcurrentHashMap<>();
    /**
     * Running sum of {@link #reservedByRegion} values per region. Maintained
     * under {@link #reservedLock} on every mutation so that unreserved
     * acquisition can read a consistent cap in O(1).
     */
    private final ConcurrentHashMap<String, AtomicInteger> regionReservedTotal = new ConcurrentHashMap<>();
    /** Unreserved inflight counters partitioned by region. */
    private final ConcurrentHashMap<String, AtomicInteger> unreservedByRegion = new ConcurrentHashMap<>();
    /** Guards atomic validate-then-set and rollback operations on the reserved state. */
    private final Object reservedLock = new Object();
    private final int regionLimit;
    private final int unreservedMin;

    @Inject
    public LambdaConcurrencyLimiter(EmulatorConfig config) {
        this(config.services().lambda().regionConcurrencyLimit(),
             config.services().lambda().unreservedConcurrencyMin());
    }

    /** Test-only constructor with explicit limits. */
    LambdaConcurrencyLimiter(int regionLimit, int unreservedMin) {
        this.regionLimit = regionLimit;
        this.unreservedMin = unreservedMin;
    }

    /** Test-only no-arg constructor using AWS defaults (1000 / 100). */
    LambdaConcurrencyLimiter() {
        this(1000, 100);
    }

    public Permit acquire(LambdaFunction fn) {
        Integer r = fn.getReservedConcurrentExecutions();
        String region = regionOf(fn.getFunctionArn());
        if (r == null) {
            return acquireUnreserved(region);
        }
        return acquireReserved(fn.getFunctionArn(), r);
    }

    private Permit acquireReserved(String key, int limit) {
        AtomicInteger counter = inflight.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                throw throttle();
            }
            if (counter.compareAndSet(current, current + 1)) {
                return idempotentPermit(counter);
            }
        }
    }

    private Permit acquireUnreserved(String region) {
        AtomicInteger counter = unreservedByRegion.computeIfAbsent(region, k -> new AtomicInteger());
        AtomicInteger reservedTotal = regionTotal(region);
        while (true) {
            int current = counter.get();
            // reservedTotal is an AtomicInteger updated under reservedLock on
            // each reserved change, so the cap is consistent and computed in
            // O(1) regardless of the number of reserved functions.
            int cap = Math.max(0, regionLimit - reservedTotal.get());
            if (current >= cap) {
                throw throttle();
            }
            if (counter.compareAndSet(current, current + 1)) {
                return idempotentPermit(counter);
            }
        }
    }

    /**
     * Wraps {@code counter.decrementAndGet()} in a close-once guard so a
     * future caller that accidentally double-closes a permit cannot drive
     * the inflight counter negative.
     */
    private static Permit idempotentPermit(AtomicInteger counter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        };
    }

    /**
     * Register (or update) a function's reserved value without validation.
     * Intended for startup rehydration from persisted state.
     *
     * @return the previous value, or {@code null} if none was set.
     */
    public Integer setReserved(String functionArn, int value) {
        synchronized (reservedLock) {
            return writeReserved(functionArn, value);
        }
    }

    /**
     * @return the cleared value, or {@code null} if no reservation was set.
     */
    public Integer clearReserved(String functionArn) {
        synchronized (reservedLock) {
            return writeReserved(functionArn, null);
        }
    }

    /**
     * Atomically validates and applies a reserved value within the function's
     * region. Two concurrent Puts for different functions cannot each pass
     * validation against stale totals and then collectively push the region's
     * unreserved capacity below the minimum.
     *
     * @return the previous reserved value for this ARN, or {@code null} if none;
     *         callers may use it with {@link #rollbackReservedIfExpected} on a
     *         subsequent persistence failure.
     * @throws AwsException {@code LimitExceededException} if the value would
     *         drop unreserved below the minimum.
     */
    public Integer validateAndSetReserved(String functionArn, int target) {
        String region = regionOf(functionArn);
        synchronized (reservedLock) {
            ConcurrentHashMap<String, Integer> regionReserved = reservedOf(region);
            int currentForThis = regionReserved.getOrDefault(functionArn, 0);
            // A reduction (or no-op) cannot decrease unreserved capacity any
            // further than it already is, so always allow it. This lets
            // operators recover from an over-committed state — e.g. after
            // lowering region-concurrency-limit at runtime — without first
            // having to delete every reservation.
            if (target > currentForThis) {
                int otherReserved = regionTotal(region).get() - currentForThis;
                int maxAllowed = regionLimit - unreservedMin - otherReserved;
                if (target > maxAllowed) {
                    throw new AwsException("LimitExceededException",
                            "Specified ReservedConcurrentExecutions for function decreases account's "
                            + "UnreservedConcurrentExecution below its minimum value of ["
                            + unreservedMin + "].", 400);
                }
            }
            return writeReserved(functionArn, target);
        }
    }

    /**
     * Conditionally restores a prior reserved value if the current value still
     * matches {@code expectedCurrent}. Used by callers that updated the limiter
     * before a persistence step and need to undo the change on failure without
     * clobbering a concurrent successful write.
     */
    public void rollbackReservedIfExpected(String functionArn,
                                           Integer expectedCurrent,
                                           Integer rollbackTo) {
        synchronized (reservedLock) {
            Integer actual = reservedOf(regionOf(functionArn)).get(functionArn);
            if (!Objects.equals(actual, expectedCurrent)) {
                // A newer update has superseded ours; leave it alone.
                return;
            }
            writeReserved(functionArn, rollbackTo);
        }
    }

    /**
     * Clears the reserved entry for a deleted function. The inflight counter
     * is intentionally retained — permits held by still-running invocations
     * decrement into it on close, and an ARN recreated later reuses the same
     * counter so new invocations correctly see any remaining inflight.
     *
     * <p>The counter is also retained even when it momentarily reads zero:
     * conditionally removing it would race with a concurrent
     * {@code acquireReserved} that has already obtained the {@code AtomicInteger}
     * reference and is about to increment it. After such a race the next
     * acquire would allocate a fresh counter and undercount the inflight
     * permit, allowing reserved over-subscription. The trade-off is one
     * {@code AtomicInteger} per historical function, which is bounded for an
     * emulator workload.
     */
    public void reset(String functionArn) {
        synchronized (reservedLock) {
            writeReserved(functionArn, null);
        }
    }

    public int totalReserved(String region) {
        return regionTotal(region).get();
    }

    public int availableUnreserved(String region) {
        AtomicInteger counter = unreservedByRegion.get(region);
        int inflightNow = counter == null ? 0 : counter.get();
        return Math.max(0, regionLimit - totalReserved(region) - inflightNow);
    }

    int inflightCount(String functionArn) {
        AtomicInteger counter = inflight.get(functionArn);
        return counter == null ? 0 : counter.get();
    }

    int unreservedInflightCount(String region) {
        AtomicInteger counter = unreservedByRegion.get(region);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Must be called with {@link #reservedLock} held. Updates the per-function
     * reserved entry and the region's running total in one atomic step from the
     * perspective of any code that also holds the lock.
     */
    private Integer writeReserved(String functionArn, Integer newValue) {
        String region = regionOf(functionArn);
        ConcurrentHashMap<String, Integer> regionReserved = reservedOf(region);
        Integer previous = newValue == null
                ? regionReserved.remove(functionArn)
                : regionReserved.put(functionArn, newValue);
        int delta = (newValue == null ? 0 : newValue) - (previous == null ? 0 : previous);
        if (delta != 0) {
            regionTotal(region).addAndGet(delta);
        }
        return previous;
    }

    private ConcurrentHashMap<String, Integer> reservedOf(String region) {
        return reservedByRegion.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
    }

    private AtomicInteger regionTotal(String region) {
        return regionReservedTotal.computeIfAbsent(region, k -> new AtomicInteger());
    }

    /**
     * Extracts the region segment from a Lambda function ARN. Falls back to
     * {@code "unknown"} if the ARN is malformed so state still partitions
     * cleanly rather than mixing with another region's data.
     *
     * <p>This is called on every acquire/release, so the parse avoids the
     * regex machinery and array allocation of {@code String.split(":")} by
     * scanning for the fourth ':' segment (index 3 in an ARN:
     * {@code arn:aws:lambda:REGION:account:function:name}).
     */
    private static String regionOf(String arn) {
        if (arn == null) {
            warnMalformed("<null>");
            return "unknown";
        }
        int segmentStart = 0;
        int delimiters = 0;
        for (int i = 0; i < arn.length(); i++) {
            if (arn.charAt(i) == ':') {
                if (delimiters == 2) {
                    segmentStart = i + 1;
                } else if (delimiters == 3) {
                    return arn.substring(segmentStart, i);
                }
                delimiters++;
            }
        }
        warnMalformed(arn);
        return "unknown";
    }

    private static void warnMalformed(String arn) {
        if (LOGGED_MALFORMED_ARNS.add(arn)) {
            LOG.warnv("Concurrency limiter received non-ARN function identifier "
                    + "\"{0}\"; bucketing under region=\"unknown\". This likely "
                    + "indicates a bare function name reached the limiter.", arn);
        }
    }

    private static AwsException throttle() {
        return new AwsException("TooManyRequestsException", "Rate Exceeded.", 429);
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
