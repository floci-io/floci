package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staging a build's source into its container streams a multi-gigabyte tar over the shared
 * docker socket. When an LZA Bootstrap stage launches its first wave of builds at once they
 * all stream simultaneously and the daemon drops connections mid-write ({@code Broken pipe}),
 * failing exactly the first {@code maxConcurrentBuilds} builds. Two guards make staging robust:
 * a source-copy semaphore serialises the heavy streaming so the wave cannot collide, and a
 * bounded retry recovers a copy that still hits a transient docker I/O error.
 */
class CodeBuildSourceStagingTest {

    private CodeBuildRunner runner(Integer maxConcurrentSourceCopies) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().maxConcurrentSourceCopies())
                .thenReturn(Optional.ofNullable(maxConcurrentSourceCopies));
        return new CodeBuildRunner(null, null, null, null, null, null, null, null, config, null, null);
    }

    // ---- source-copy serialisation slots ----

    @Test
    void sourceCopyDefaultsToSerialisedWhenUnset() {
        Semaphore slots = runner(null).sourceCopySlots();
        assertNotNull(slots);
        assertEquals(CodeBuildRunner.DEFAULT_MAX_CONCURRENT_SOURCE_COPIES, slots.availablePermits());
    }

    @Test
    void sourceCopyBoundedWhenSet() {
        Semaphore slots = runner(3).sourceCopySlots();
        assertNotNull(slots);
        assertEquals(3, slots.availablePermits());
    }

    @Test
    void sourceCopyNonPositiveIsUnbounded() {
        assertNull(runner(0).sourceCopySlots());
    }

    // ---- staging-slot gate shared by container-create and source-copy ----

    @Test
    void stagingSlotIsHeldDuringOpAndReleasedAfter() throws Exception {
        CodeBuildRunner r = runner(1);
        Semaphore slots = r.sourceCopySlots();
        assertEquals(1, slots.availablePermits());
        String result = r.underStagingSlot(() -> {
            assertEquals(0, slots.availablePermits(), "slot must be held while the op runs");
            return "done";
        });
        assertEquals("done", result);
        assertEquals(1, slots.availablePermits(), "slot must be released after the op");
    }

    @Test
    void stagingSlotReleasedWhenOpThrows() {
        CodeBuildRunner r = runner(1);
        Semaphore slots = r.sourceCopySlots();
        assertThrows(RuntimeException.class,
                () -> r.underStagingSlot(() -> { throw new RuntimeException("boom"); }));
        assertEquals(1, slots.availablePermits(), "slot must be released even when the op throws");
    }

    @Test
    void stagingSlotIsNoOpWhenUnbounded() throws Exception {
        CodeBuildRunner r = runner(0);
        assertNull(r.sourceCopySlots());
        assertEquals("ok", r.underStagingSlot(() -> "ok"));
    }

    // ---- transient docker I/O classification ----

    @Test
    void brokenPipeNestedInRuntimeExceptionIsTransient() {
        Throwable wrapped = new RuntimeException(new IOException("Broken pipe"));
        assertTrue(CodeBuildRunner.isTransientDockerIo(wrapped));
    }

    @Test
    void directIoExceptionIsTransient() {
        assertTrue(CodeBuildRunner.isTransientDockerIo(new IOException("connection reset")));
    }

    @Test
    void plainRuntimeExceptionIsNotTransient() {
        assertFalse(CodeBuildRunner.isTransientDockerIo(new RuntimeException("bad request")));
    }

    // ---- bounded retry ----

    @Test
    void retryRecoversAfterTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CodeBuildRunner.retryTransientDockerIo(4, 0L, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
        });
        assertEquals(3, calls.get());
    }

    @Test
    void retryExhaustsThenThrowsOnPersistentTransient() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException boom = assertThrows(RuntimeException.class,
                () -> CodeBuildRunner.retryTransientDockerIo(3, 0L, () -> {
                    calls.incrementAndGet();
                    throw new RuntimeException(new IOException("Broken pipe"));
                }));
        assertEquals(3, calls.get());
        assertTrue(boom.getMessage() == null || boom.getMessage().contains("attempt")
                || CodeBuildRunner.isTransientDockerIo(boom), boom.toString());
    }

    @Test
    void retryDoesNotRetryNonTransient() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("nope");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> CodeBuildRunner.retryTransientDockerIo(5, 0L, () -> {
                    calls.incrementAndGet();
                    throw expected;
                }));
        assertEquals(1, calls.get());
        assertSame(expected, thrown.getCause() == null ? thrown : thrown.getCause());
    }
}
