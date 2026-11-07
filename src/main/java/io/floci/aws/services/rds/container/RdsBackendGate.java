package io.floci.aws.services.rds.container;

/**
 * Lets a client into an RDS backend container. An Aurora Serverless v2 cluster that auto-paused is
 * resumed first, and it does not pause again while the client holds the returned lease.
 */
@FunctionalInterface
public interface RdsBackendGate {

    /** A gate for backends that never pause. */
    RdsBackendGate OPEN = runtimeId -> Lease.NONE;

    /**
     * Waits until the container owned by {@code runtimeId} can take a client, resuming it when it
     * is auto-paused, and keeps it awake until the lease is closed. A backend with no auto-pause
     * gets {@link Lease#NONE}.
     *
     * @throws InterruptedException when interrupted while a pause or resume is under way
     */
    Lease enterRuntime(String runtimeId) throws InterruptedException;

    /**
     * Like {@link #enterRuntime(String)}, but with {@code waitForResume=false} starts resume in the
     * background and throws {@link ResumingException} if the runtime is not yet ready.
     */
    default Lease enterRuntime(String runtimeId, boolean waitForResume) throws InterruptedException {
        return enterRuntime(runtimeId);
    }

    /** The backend is being resumed; no lease was acquired and the caller must retry. */
    class ResumingException extends RuntimeException {
    }

    /** Held for as long as a client uses the backend; closing it more than once is harmless. */
    interface Lease extends AutoCloseable {

        Lease NONE = () -> {
        };

        @Override
        void close();
    }
}
