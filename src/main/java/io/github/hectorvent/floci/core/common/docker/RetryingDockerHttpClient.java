package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;

/**
 * Decorates a {@link DockerHttpClient} with the {@link DockerRetry} policy so every docker call
 * travelling over the shared daemon socket survives a transient mid-call drop ({@code Broken
 * pipe}, {@code Connection reset}) without each call site having to wrap itself.
 *
 * <p>The daemon drops connections under fan-out load (an LZA Bootstrap stage firing ~15 CodeBuild
 * actions, a Prepare stage at six live containers), and docker-java surfaces the blip as
 * {@code RuntimeException(IOException)} from inside {@code execute()} — which is exactly where
 * this decorator sits. Guarding call sites one at a time was fixed three separate times
 * ({@code createContainerCmd}, {@code startContainerCmd}, {@code ensureVolume}) while ~60 other
 * docker call sites stayed bare; one guard at the transport boundary covers them all, including
 * every call added later.
 *
 * <p>A request is only retried when replaying it cannot change semantics:
 * <ul>
 *   <li>Its body, if any, must live in {@code bodyBytes()} — a plain {@code byte[]} the transport
 *       re-reads from scratch on every attempt. A request whose body is only available as a
 *       one-shot {@code InputStream} (the tar upload of {@code copyArchiveToContainerCmd}) may
 *       have been partially consumed by the failed attempt and is never replayed.</li>
 *   <li>It must not carry {@code hijackedInput()} (bidirectional attach streams).</li>
 *   <li>Its path must not contain {@code /exec}: {@code POST /exec/{id}/start} re-runs a shell
 *       command whose first run may have executed before the socket died — a worse bug than the
 *       one retrying fixes. The {@code contains} spelling (not {@code startsWith}) also covers
 *       exec-create ({@code POST /containers/{id}/exec}), where a replay would merely leak an
 *       unused exec ID; that conservative breadth costs nothing.</li>
 * </ul>
 *
 * <p>Idempotency of what a replay <em>means</em> stays the call site's job: docker answers a
 * replayed start of an already-running container with HTTP 304 (raised above this transport as
 * {@code NotModifiedException} and treated as success by the caller), and {@code ensureVolume}
 * re-checks existence so a replayed create finds the volume the lost response landed.
 */
public final class RetryingDockerHttpClient implements DockerHttpClient {

    /**
     * Mirrors the per-call-site policy this decorator replaces: six attempts at 500ms
     * exponential backoff (capped by {@link DockerRetry#BACKOFF_CAP_MS}) rides out the
     * several-second socket saturation a fan-out build wave causes.
     */
    static final int MAX_ATTEMPTS = 6;
    static final long BACKOFF_MS = 500L;

    private final DockerHttpClient delegate;
    private final int maxAttempts;
    private final long backoffMillis;

    public RetryingDockerHttpClient(DockerHttpClient delegate) {
        this(delegate, MAX_ATTEMPTS, BACKOFF_MS);
    }

    RetryingDockerHttpClient(DockerHttpClient delegate, int maxAttempts, long backoffMillis) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    @Override
    public Response execute(Request request) {
        if (!isReplayable(request)) {
            return delegate.execute(request);
        }
        return DockerRetry.call(maxAttempts, backoffMillis, () -> delegate.execute(request));
    }

    /**
     * Whether re-executing {@code request} after a transient failure is semantically safe.
     * {@code bodyBytes()} takes precedence over {@code body()} exactly as it does in the
     * transport itself: when both are set the transport sends the bytes and never touches the
     * stream, so the request replays cleanly.
     */
    static boolean isReplayable(Request request) {
        if (request.hijackedInput() != null) {
            return false;
        }
        if (request.path().contains("/exec")) {
            return false;
        }
        return request.bodyBytes() != null || request.body() == null;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
