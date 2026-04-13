package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    /** Unreserved inflight counters partitioned by region. */
    private final ConcurrentHashMap<String, AtomicInteger> unreservedByRegion = new ConcurrentHashMap<>();
    /** Guards atomic validate-then-set operations on the reserved maps. */
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
        while (true) {
            int current = counter.get();
            // Recompute cap each spin so a concurrent setReserved is observed
            // promptly and we do not grant permits above the live pool.
            int cap = Math.max(0, regionLimit - totalReserved(region));
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
            return reservedOf(regionOf(functionArn)).put(functionArn, value);
        }
    }

    /**
     * @return the cleared value, or {@code null} if no reservation was set.
     */
    public Integer clearReserved(String functionArn) {
        synchronized (reservedLock) {
            return reservedOf(regionOf(functionArn)).remove(functionArn);
        }
    }

    /**
     * Atomically validates and applies a reserved value within the function's
     * region. Two concurrent Puts for different functions cannot each pass
     * validation against stale totals and then collectively push the region's
     * unreserved capacity below the minimum.
     *
     * @return the previous reserved value for this ARN, or {@code null} if none;
     *         callers may use it to roll back on a subsequent persistence failure.
     * @throws AwsException {@code LimitExceededException} if the value would
     *         drop unreserved below the minimum.
     */
    public Integer validateAndSetReserved(String functionArn, int target) {
        String region = regionOf(functionArn);
        synchronized (reservedLock) {
            ConcurrentHashMap<String, Integer> regionReserved = reservedOf(region);
            int otherReserved = sum(regionReserved) - regionReserved.getOrDefault(functionArn, 0);
            int maxAllowed = regionLimit - unreservedMin - otherReserved;
            if (target > maxAllowed) {
                throw new AwsException("LimitExceededException",
                        "Specified ReservedConcurrentExecutions for function decreases account's "
                        + "UnreservedConcurrentExecution below its minimum value of ["
                        + unreservedMin + "].", 400);
            }
            return regionReserved.put(functionArn, target);
        }
    }

    /** Clears both inflight and reserved entries for a deleted function. */
    public void reset(String functionArn) {
        inflight.remove(functionArn);
        synchronized (reservedLock) {
            reservedOf(regionOf(functionArn)).remove(functionArn);
        }
    }

    public int totalReserved(String region) {
        return sum(reservedOf(region));
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

    private ConcurrentHashMap<String, Integer> reservedOf(String region) {
        return reservedByRegion.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
    }

    private static int sum(ConcurrentHashMap<String, Integer> map) {
        int s = 0;
        for (Integer v : map.values()) {
            s += v;
        }
        return s;
    }

    /**
     * Extracts the region segment from a Lambda function ARN. Falls back to
     * {@code "unknown"} if the ARN is malformed so state still partitions
     * cleanly rather than mixing with another region's data.
     */
    private static String regionOf(String arn) {
        if (arn == null) {
            return "unknown";
        }
        String[] parts = arn.split(":");
        return parts.length >= 4 ? parts[3] : "unknown";
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
