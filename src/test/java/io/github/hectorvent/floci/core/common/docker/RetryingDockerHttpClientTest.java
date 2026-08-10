package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;
import com.github.dockerjava.transport.DockerHttpClient.Response;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * The docker daemon drops connections mid-call under fan-out load, and docker-java surfaces the
 * blip as {@code RuntimeException(IOException("Broken pipe"))} from inside
 * {@code DockerHttpClient.execute()}. Retrying per call site has now been fixed three separate
 * times ({@code createContainerCmd}, {@code startContainerCmd}, {@code ensureVolume}) while ~60
 * other docker call sites stayed bare. {@link RetryingDockerHttpClient} moves the retry to the
 * transport seam so every call site — present and future — is covered once.
 *
 * <p>The retry may only fire when replaying the request cannot change semantics: the request must
 * be replayable (no one-shot body stream, no hijacked stdin) and must not be an exec-start, which
 * would re-run a shell command whose first run may have executed.
 */
class RetryingDockerHttpClientTest {

    private static final int MAX_ATTEMPTS = 3;

    /** A delegate whose {@code execute} defers to {@code behaviour}, handed the 1-based attempt. */
    private static final class FakeTransport implements DockerHttpClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Request> seenRequests = new ArrayList<>();
        final IntFunction<Response> behaviour;

        FakeTransport(IntFunction<Response> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Response execute(Request request) {
            seenRequests.add(request);
            return behaviour.apply(calls.incrementAndGet());
        }

        @Override
        public void close() {
        }
    }

    private static RuntimeException brokenPipe() {
        return new RuntimeException(new IOException("Broken pipe"));
    }

    @Test
    void retriesTransientBrokenPipeOnBodylessRequestThenSucceeds() {
        Response ok = mock(Response.class);
        FakeTransport delegate = new FakeTransport(attempt -> {
            if (attempt < 3) {
                throw brokenPipe();
            }
            return ok;
        });
        RetryingDockerHttpClient client = new RetryingDockerHttpClient(delegate, MAX_ATTEMPTS, 0L);

        Request ping = Request.builder().method(Request.Method.GET).path("/_ping").build();

        assertSame(ok, client.execute(ping));
        assertEquals(3, delegate.calls.get(),
                "a transient Broken pipe on a bodyless GET must be retried at the transport");
    }

    @Test
    void retriesByteArrayBodiedRequestReplayingIdenticalBytes() {
        Response ok = mock(Response.class);
        FakeTransport delegate = new FakeTransport(attempt -> {
            if (attempt == 1) {
                throw brokenPipe();
            }
            return ok;
        });
        RetryingDockerHttpClient client = new RetryingDockerHttpClient(delegate, MAX_ATTEMPTS, 0L);

        byte[] body = "{\"Image\":\"busybox\"}".getBytes(StandardCharsets.UTF_8);
        Request create = Request.builder()
                .method(Request.Method.POST)
                .path("/containers/create")
                .bodyBytes(body)
                .build();

        assertSame(ok, client.execute(create));
        assertEquals(2, delegate.calls.get(),
                "a POST whose body is a plain byte[] is replayable and must be retried");
        // This is the replay-safety proof, not a formality: the transport builds a fresh entity
        // from bodyBytes() on every execute(), so both attempts must see the very same bytes.
        assertEquals(2, delegate.seenRequests.size());
        assertEquals(Arrays.hashCode(delegate.seenRequests.get(0).bodyBytes()),
                Arrays.hashCode(delegate.seenRequests.get(1).bodyBytes()),
                "the retried attempt must carry byte-identical body content");
        assertEquals("/containers/create", delegate.seenRequests.get(1).path());
    }

    @Test
    void doesNotRetryOneShotStreamBody() {
        FakeTransport delegate = new FakeTransport(attempt -> {
            throw brokenPipe();
        });
        RetryingDockerHttpClient client = new RetryingDockerHttpClient(delegate, MAX_ATTEMPTS, 0L);

        // The tar upload of copyArchiveToContainerCmd: bodyBytes is null, body() is a one-shot
        // stream that the first (failed) attempt may have partially consumed. Replaying it would
        // send a truncated archive, so the failure must surface after exactly one attempt.
        InputStream tar = new ByteArrayInputStream(new byte[]{1, 2, 3});
        Request putArchive = Request.builder()
                .method(Request.Method.PUT)
                .path("/containers/abc/archive")
                .body(tar)
                .build();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.execute(putArchive));
        assertEquals("Broken pipe", thrown.getCause().getMessage());
        assertEquals(1, delegate.calls.get(),
                "a one-shot stream body cannot be replayed; the transport must not retry it");
    }

    @Test
    void doesNotRetryHijackedRequest() {
        FakeTransport delegate = new FakeTransport(attempt -> {
            throw brokenPipe();
        });
        RetryingDockerHttpClient client = new RetryingDockerHttpClient(delegate, MAX_ATTEMPTS, 0L);

        Request attach = Request.builder()
                .method(Request.Method.POST)
                .path("/containers/abc/attach")
                .hijackedInput(new ByteArrayInputStream(new byte[0]))
                .build();

        assertThrows(RuntimeException.class, () -> client.execute(attach));
        assertEquals(1, delegate.calls.get(),
                "a hijacked (bidirectional stdin) request must never be replayed");
    }

    @Test
    void doesNotRetryExecPathsEvenWhenReplayable() {
        // POST /exec/{id}/start carries a small JSON body, so by the replayable-body rule alone it
        // would be retried — re-running a shell command whose first run may have executed. Exec
        // paths are excluded outright.
        FakeTransport startDelegate = new FakeTransport(attempt -> {
            throw brokenPipe();
        });
        RetryingDockerHttpClient startClient =
                new RetryingDockerHttpClient(startDelegate, MAX_ATTEMPTS, 0L);
        Request execStart = Request.builder()
                .method(Request.Method.POST)
                .path("/exec/deadbeef/start")
                .bodyBytes("{}".getBytes(StandardCharsets.UTF_8))
                .build();

        assertThrows(RuntimeException.class, () -> startClient.execute(execStart));
        assertEquals(1, startDelegate.calls.get(),
                "exec-start re-runs the command if replayed; it must surface after one attempt");

        // The exclusion is contains("/exec"), not startsWith: exec-create
        // (POST /containers/{id}/exec) is also excluded, and this pins that breadth so a later
        // refactor to startsWith cannot pass CI.
        FakeTransport createDelegate = new FakeTransport(attempt -> {
            throw brokenPipe();
        });
        RetryingDockerHttpClient createClient =
                new RetryingDockerHttpClient(createDelegate, MAX_ATTEMPTS, 0L);
        Request execCreate = Request.builder()
                .method(Request.Method.POST)
                .path("/containers/abc/exec")
                .bodyBytes("{}".getBytes(StandardCharsets.UTF_8))
                .build();

        assertThrows(RuntimeException.class, () -> createClient.execute(execCreate));
        assertEquals(1, createDelegate.calls.get(),
                "exec-create must be excluded too — the /exec exclusion covers both spellings");
    }

    @Test
    void doesNotRetryNonTransientFailures() {
        // Pool exhaustion: retrying re-enters another full connection-request wait and adds
        // pressure to an already-starved pool. isTransientIo deliberately excludes it.
        RuntimeException poolExhausted =
                new RuntimeException(new ConnectionRequestTimeoutException("no free lease"));
        FakeTransport exhaustedDelegate = new FakeTransport(attempt -> {
            throw poolExhausted;
        });
        RetryingDockerHttpClient exhaustedClient =
                new RetryingDockerHttpClient(exhaustedDelegate, MAX_ATTEMPTS, 0L);
        Request ping = Request.builder().method(Request.Method.GET).path("/_ping").build();

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> exhaustedClient.execute(ping));
        assertSame(poolExhausted, thrown, "pool exhaustion must surface unchanged");
        assertEquals(1, exhaustedDelegate.calls.get(), "pool exhaustion must not be retried");

        // A genuine daemon rejection (e.g. a 409 name conflict raised above the transport as a
        // DockerException) never clears on a retry either.
        IllegalStateException rejection = new IllegalStateException("Conflict: name already in use");
        FakeTransport rejectedDelegate = new FakeTransport(attempt -> {
            throw rejection;
        });
        RetryingDockerHttpClient rejectedClient =
                new RetryingDockerHttpClient(rejectedDelegate, MAX_ATTEMPTS, 0L);

        IllegalStateException surfaced =
                assertThrows(IllegalStateException.class, () -> rejectedClient.execute(ping));
        assertSame(rejection, surfaced, "a daemon rejection must surface unchanged");
        assertEquals(1, rejectedDelegate.calls.get());
    }

    @Test
    void producerWrapsControlPlaneTransportOnly() {
        DockerHttpClient raw = new FakeTransport(attempt -> null);

        DockerHttpClient controlPlane = DockerClientProducer.wrapForRole(raw, "control-plane");
        DockerHttpClient streaming = DockerClientProducer.wrapForRole(raw, "streaming");

        assertSame(RetryingDockerHttpClient.class, controlPlane.getClass(),
                "the control-plane transport must be wrapped in the retry decorator");
        assertNotSame(raw, controlPlane);
        assertSame(raw, streaming,
                "the streaming transport (log-follow, exec output) must stay unwrapped");
    }
}
