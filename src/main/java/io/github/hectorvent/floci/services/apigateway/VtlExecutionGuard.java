package io.github.hectorvent.floci.services.apigateway;

import java.time.Duration;
import java.time.Instant;

/**
 * Tracks a wall-clock execution deadline for the VTL template render currently in progress on
 * the calling thread.
 *
 * <p>Both {@link VtlTemplateEngine} and the AppSync VTL engine call {@link #begin(Duration)}
 * immediately before delegating to Velocity's {@code VelocityEngine.evaluate(...)}, and
 * {@link #end()} in a {@code finally} block right after. {@link SandboxedForeach} calls
 * {@link #checkDeadline()} before rendering each loop iteration's body, which is what actually
 * stops a runaway {@code #foreach} (whether over a huge range, or one whose body does something
 * increasingly expensive per iteration, such as {@code #set} string doubling): each iteration's
 * own cost grows with the doubling, so the next iteration's check reliably trips shortly after
 * the configured time budget is crossed, well before memory would otherwise be exhausted.
 *
 * <p>A {@link ThreadLocal} is used because template evaluation is synchronous and single-threaded
 * per call, and Velocity does not offer a per-render context slot for deadline plumbing that both
 * a custom writer and a custom directive can reach without changing Velocity's own APIs.
 */
public final class VtlExecutionGuard {

    private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

    private VtlExecutionGuard() {
    }

    /** Starts a new deadline, {@code timeout} from now, for the current thread. */
    public static void begin(Duration timeout) {
        DEADLINE.set(Instant.now().plus(timeout));
    }

    /** Clears the current thread's deadline. Must be called after every {@link #begin(Duration)}. */
    public static void end() {
        DEADLINE.remove();
    }

    /**
     * Throws {@link VtlLimitExceededException} if the current thread has an active deadline that
     * has already passed. Does nothing if no deadline is active.
     */
    public static void checkDeadline() {
        Instant deadline = DEADLINE.get();
        if (deadline != null && Instant.now().isAfter(deadline)) {
            throw new VtlLimitExceededException("VTL template execution exceeded the configured time limit");
        }
    }
}
