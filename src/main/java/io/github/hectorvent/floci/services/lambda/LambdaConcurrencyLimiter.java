package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces per-function reserved concurrency limits at invocation time.
 *
 * <p>Callers invoke {@link #acquire(LambdaFunction)} before dispatching work and
 * close the returned {@link Permit} when the work completes. Functions without a
 * reserved value are not enforced at this layer (account-level enforcement is a
 * future step).
 */
@ApplicationScoped
public class LambdaConcurrencyLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    public Permit acquire(LambdaFunction fn) {
        Integer reserved = fn.getReservedConcurrentExecutions();
        if (reserved == null) {
            return Permit.NOOP;
        }
        String key = fn.getFunctionArn();
        AtomicInteger counter = inflight.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= reserved) {
                throw new AwsException("TooManyRequestsException", "Rate Exceeded.", 429);
            }
            if (counter.compareAndSet(current, current + 1)) {
                return () -> counter.decrementAndGet();
            }
        }
    }

    public void reset(String functionArn) {
        inflight.remove(functionArn);
    }

    int inflightCount(String functionArn) {
        AtomicInteger counter = inflight.get(functionArn);
        return counter == null ? 0 : counter.get();
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        Permit NOOP = () -> { };

        @Override
        void close();
    }
}
