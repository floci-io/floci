package io.github.hectorvent.floci.services.lambda;

import java.util.function.Supplier;

/**
 * Where the current thread sits in a Lambda chain of requests, the sequence of invocations that
 * one originating event causes.
 *
 * <p>AWS tracks this out of band, in the X-Ray tracing header it annotates every event with, and
 * stops the next invocation once a function has been invoked roughly {@value #MAX_DEPTH} times in
 * the same chain. Floci has no tracing header to hang the count on, and a member added to the
 * event would change what the function receives, which has to stay exactly what AWS sends. The
 * calling thread carries it instead: SNS delivers to a Lambda subscriber, and EventBridge to a
 * Lambda target, synchronously on the thread that published, so a destination record looping back
 * into Lambda through either of them arrives on the thread that delivered it.
 * {@code EventBridgeInvoker} bounds bus-to-bus forwarding the same way.
 *
 * <p>The bound therefore holds even when the async pool is saturated and its caller-runs fallback
 * puts the next invocation on the publishing thread's own stack, which is the case that would
 * otherwise recurse until the stack ran out.
 *
 * <p>A cycle that leaves the thread is not counted and is not bounded here. An SQS event source
 * mapping is the one that matters: its poller runs on a thread of its own, and AWS closes that gap
 * with the same tracing header, carried as an SQS system attribute.
 */
final class LambdaInvocationChain {

    /**
     * How many invocations one originating event may cause before the next one is dropped. AWS
     * stops a chain at approximately this many.
     */
    static final int MAX_DEPTH = 16;

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private LambdaInvocationChain() {}

    /** How many invocations already led to the work running on this thread. */
    static int currentDepth() {
        return DEPTH.get();
    }

    /** Whether an invocation at {@code depth} is the one past the bound, and so has to be dropped. */
    static boolean exhausted(int depth) {
        return depth >= MAX_DEPTH;
    }

    /**
     * Runs {@code body} at the given position in the chain, so everything it reaches synchronously,
     * a re-entrant Lambda invocation included, counts from there rather than starting over.
     */
    static <T> T callAtDepth(int depth, Supplier<T> body) {
        int previous = DEPTH.get();
        DEPTH.set(depth);
        try {
            return body.get();
        } finally {
            // Pool and request threads are reused, so the counter cannot be left behind on one.
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }
}
