package io.floci.aws.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bounds how many container creates and starts reach the Docker daemon at once. A burst of container
 * work (a pipeline stage starting a dozen containers, a fan-out of Lambda cold starts) otherwise hits
 * the daemon all at once and relies on the retry layer to absorb what it drops. Every other call,
 * including image pulls (already one per image) and container stops and removes, passes straight
 * through.
 *
 * <p>It sits in the control-plane transport, under {@link RetryingDockerHttpClient}, so a permit is held
 * for exactly one HTTP request: never across a pull, a network attach, a file copy or a retry's backoff,
 * and never twice by one thread, so a launch that starts a helper container inside it cannot deadlock.
 * Every create and start goes through here, including the few call sites that bypass
 * {@code ContainerLifecycleManager}. A call that cannot get a permit within the wait fails with
 * {@link LaunchSlotTimeoutException}, which the retry layer does not replay.
 */
final class LaunchBulkheadDockerHttpClient implements DockerHttpClient {

    private static final Pattern LAUNCH_ROUTE = Pattern.compile("(/v[0-9.]+)?/containers/(create|[^/]+/start)");

    private final DockerHttpClient delegate;
    private final Semaphore slots;
    private final int size;
    private final Duration acquireTimeout;

    LaunchBulkheadDockerHttpClient(DockerHttpClient delegate, int size, Duration acquireTimeout) {
        this.delegate = delegate;
        this.slots = new Semaphore(size, true);
        this.size = size;
        this.acquireTimeout = acquireTimeout;
    }

    /** {@code floci.docker.max-concurrent-launches}: 0 means twice the CPUs Floci sees, at least 4. */
    static int resolveSize(int configured, int availableProcessors) {
        return configured > 0 ? configured : Math.max(4, 2 * availableProcessors);
    }

    /** Raised when no launch slot frees up within the wait; an {@link InterruptedIOException}, so it is never retried. */
    static final class LaunchSlotTimeoutException extends InterruptedIOException {
        LaunchSlotTimeoutException(String message) {
            super(message);
        }
    }

    @Override
    public Response execute(Request request) {
        if (!isLaunch(request)) {
            return delegate.execute(request);
        }
        acquire(request);
        try {
            return delegate.execute(request);
        } finally {
            slots.release();
        }
    }

    private void acquire(Request request) {
        try {
            if (!slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RuntimeException(new LaunchSlotTimeoutException("No container launch slot freed up within "
                        + acquireTimeout.toSeconds() + " s: " + size + " container creates and starts are already"
                        + " running (floci.docker.max-concurrent-launches); not sent: " + request.method() + " "
                        + route(request.path())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for a container launch slot", e);
        }
    }

    /** {@code POST /containers/create} or {@code POST /containers/{id}/start}, after an optional API version. */
    static boolean isLaunch(Request request) {
        return "POST".equals(request.method()) && LAUNCH_ROUTE.matcher(route(request.path())).matches();
    }

    int availableSlots() {
        return slots.availablePermits();
    }

    private static String route(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
