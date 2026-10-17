package io.github.hectorvent.floci.services.rds.container;

/**
 * Lets a client into an RDS backend container. An Aurora Serverless v2 cluster that auto-paused is
 * resumed first, and it does not pause again while the client holds the returned lease.
 */
@FunctionalInterface
public interface RdsBackendGate {

    /** A gate for backends that never pause. */
    RdsBackendGate OPEN = (host, port) -> Lease.NONE;

    /**
     * Waits until the container serving {@code host:port} can take a client, resuming it when it
     * is auto-paused, and keeps it awake until the lease is closed. A backend with no auto-pause
     * gets {@link Lease#NONE}.
     *
     * @throws InterruptedException when interrupted while a pause or resume is under way
     */
    Lease enter(String host, int port) throws InterruptedException;

    /**
     * Like {@link #enter(String, int)}, but with {@code waitForResume=false} starts resume in the
     * background and throws {@link ResumingException} if the backend is not yet ready.
     * Backends without auto-pause may use this default implementation.
     */
    default Lease enter(String host, int port, boolean waitForResume) throws InterruptedException {
        return enter(host, port);
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
