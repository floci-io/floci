package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Inflight counts per function ARN (globally unique). */
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
                return counter::decrementAndGet;
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
                return counter::decrementAndGet;
            }
        }
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
            int otherReserved = regionTotal(region).get()
                    - regionReserved.getOrDefault(functionArn, 0);
            int maxAllowed = regionLimit - unreservedMin - otherReserved;
            if (target > maxAllowed) {
                throw new AwsException("LimitExceededException",
                        "Specified ReservedConcurrentExecutions for function decreases account's "
                        + "UnreservedConcurrentExecution below its minimum value of ["
                        + unreservedMin + "].", 400);
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
     * is intentionally retained: permits held by still-running invocations
     * captured a reference to the existing counter and must decrement into it
     * on close. If the same ARN is later recreated, the counter is reused so
     * new invocations see any remaining inflight rather than starting from
     * zero and allowing transient over-subscription.
     *
     * <p>Idle counters (value 0) are dropped so long-lived emulator runs do
     * not accumulate stale entries.
     */
    public void reset(String functionArn) {
        AtomicInteger counter = inflight.get(functionArn);
        if (counter != null && counter.get() == 0) {
            inflight.remove(functionArn);
        }
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
        return "unknown";
    }

    private static AwsException throttle() {
        return new AwsException("TooManyRequestsException", "Rate Exceeded.", 429);
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        Permit NOOP = () -> { };

        @Override
        void close();
    }
}
