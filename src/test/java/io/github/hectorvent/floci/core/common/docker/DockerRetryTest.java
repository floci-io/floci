package io.github.hectorvent.floci.core.common.docker;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker daemon calls made concurrently over the single shared socket intermittently fail with
 * a transient I/O error ({@code Broken pipe} / {@code Connection reset}) that docker-java wraps
 * in a {@link RuntimeException}. Such failures clear on a retry; anything else must surface
 * immediately. {@link DockerRetry} centralises that policy for every docker call site.
 */
class DockerRetryTest {

    @Test
    void brokenPipeNestedInRuntimeExceptionIsTransient() {
        assertTrue(DockerRetry.isTransientIo(new RuntimeException(new IOException("Broken pipe"))));
    }

    @Test
    void connectionResetIsTransient() {
        assertTrue(DockerRetry.isTransientIo(new RuntimeException("Connection reset by peer")));
    }

    @Test
    void directIoExceptionIsTransient() {
        assertTrue(DockerRetry.isTransientIo(new IOException("Broken pipe")));
    }

    @Test
    void plainRuntimeExceptionIsNotTransient() {
        assertFalse(DockerRetry.isTransientIo(new RuntimeException("400 Bad Request")));
    }

    // ConnectionRequestTimeoutException extends InterruptedIOException extends IOException, so the
    // old "any IOException in the cause chain is transient" rule wrongly retried it. It means the
    // httpclient5 pool has no free connection/lease after waiting the full request timeout — the
    // pool is exhausted, not the socket blipping. Retrying just re-enters another full wait and
    // adds more pressure to an already-starved pool, so this must NOT be classified as transient.
    @Test
    void connectionRequestTimeoutIsNotTransient() {
        assertFalse(DockerRetry.isTransientIo(new ConnectionRequestTimeoutException("Timeout waiting for connection from pool")));
    }

    // A plain InterruptedIOException (e.g. from Thread.interrupt() during a blocking read) signals
    // cancellation/shutdown, not a socket blip. Retrying it masks the interrupt and delays
    // shutdown, so it must not be classified as transient either — same rule as its
    // ConnectionRequestTimeoutException subtype above, just not pool-exhaustion-specific.
    @Test
    void plainInterruptedIoExceptionIsNotTransient() {
        assertFalse(DockerRetry.isTransientIo(new InterruptedIOException("interrupted")));
    }

    // A single forward walk returns true as soon as it hits the outer IOException, never
    // reaching the InterruptedIOException wrapped inside it — the exclusion must be checked
    // over the whole chain before the general "any IOException is transient" rule applies.
    @Test
    void interruptedIoExceptionWrappedInAnotherIOExceptionIsStillNotTransient() {
        assertFalse(DockerRetry.isTransientIo(
                new IOException("wrapper", new InterruptedIOException("interrupted"))));
    }

    // java.net.SocketTimeoutException extends InterruptedIOException, but a slow daemon
    // response is a socket blip like Broken pipe/Connection reset, not a genuine interrupt or
    // pool exhaustion -- it must stay retryable.
    @Test
    void socketTimeoutExceptionIsStillTransientDespiteExtendingInterruptedIOException() {
        assertTrue(DockerRetry.isTransientIo(new java.net.SocketTimeoutException("Read timed out")));
    }

    @Test
    void recoversAfterTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        DockerRetry.run(4, 0L, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
        });
        assertEquals(3, calls.get());
    }

    @Test
    void returnsValueFromSupplierVariant() {
        AtomicInteger calls = new AtomicInteger();
        String result = DockerRetry.call(3, 0L, () -> {
            if (calls.incrementAndGet() < 2) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    void exhaustsThenRethrowsLastTransient() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException boom = assertThrows(RuntimeException.class,
                () -> DockerRetry.run(3, 0L, () -> {
                    calls.incrementAndGet();
                    throw new RuntimeException(new IOException("Broken pipe"));
                }));
        assertEquals(3, calls.get());
        assertTrue(DockerRetry.isTransientIo(boom));
    }

    @Test
    void backoffIsExponentialAndCapped() {
        assertEquals(500L, DockerRetry.backoffDelay(1, 500L, 8000L));
        assertEquals(1000L, DockerRetry.backoffDelay(2, 500L, 8000L));
        assertEquals(2000L, DockerRetry.backoffDelay(3, 500L, 8000L));
        assertEquals(4000L, DockerRetry.backoffDelay(4, 500L, 8000L));
        assertEquals(8000L, DockerRetry.backoffDelay(5, 500L, 8000L)); // 8000 hits cap
        assertEquals(8000L, DockerRetry.backoffDelay(9, 500L, 8000L)); // capped, no overflow
        assertEquals(0L, DockerRetry.backoffDelay(3, 0L, 8000L));      // zero base stays zero
    }

    @Test
    void doesNotRetryNonTransient() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("nope");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> DockerRetry.run(5, 0L, () -> {
                    calls.incrementAndGet();
                    throw expected;
                }));
        assertEquals(1, calls.get());
        assertSame(expected, thrown);
    }

    @Test
    void rejectsMaxAttemptsBelowOne() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> DockerRetry.run(0, 0L, calls::incrementAndGet));
        assertThrows(IllegalArgumentException.class,
                () -> DockerRetry.run(-1, 0L, calls::incrementAndGet));
        // The misconfiguration must fail loudly, not silently skip the call entirely.
        assertEquals(0, calls.get());
    }

    @Test
    void brokenPipeMatchesRegardlessOfCase() {
        // The daemon's capitalisation is not a contract, and the default locale must not decide
        // whether a transient drop is recognised.
        assertTrue(DockerRetry.isTransientIo(new RuntimeException("broken pipe")));
        assertTrue(DockerRetry.isTransientIo(new RuntimeException("BROKEN PIPE")));
        assertTrue(DockerRetry.isTransientIo(new RuntimeException("Broken Pipe")));
    }
}
