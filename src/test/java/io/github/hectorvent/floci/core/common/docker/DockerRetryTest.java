package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
}
