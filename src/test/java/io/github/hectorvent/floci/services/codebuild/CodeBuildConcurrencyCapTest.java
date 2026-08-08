package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The maxConcurrentBuilds cap gates the build body with a shared semaphore. Because every
 * floci CodeBuild build stages its whole source workspace on the single emulator container's
 * filesystem, an unset cap falls back to a bounded default so a fan-out stage (LZA bootstraps
 * ~15 targets at once) cannot exhaust the shared disk. A set positive value bounds to that
 * value; a non-positive value opts back into unbounded for a well-resourced host.
 */
class CodeBuildConcurrencyCapTest {

    private CodeBuildRunner runner(Integer maxConcurrentBuilds) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().maxConcurrentBuilds())
                .thenReturn(Optional.ofNullable(maxConcurrentBuilds));
        return new CodeBuildRunner(null, null, null, null, null, null, null, config, null, null);
    }

    @Test
    void defaultsToBoundedCapWhenUnset() {
        Semaphore slots = runner(null).buildSlots();
        assertNotNull(slots);
        assertEquals(CodeBuildRunner.DEFAULT_MAX_CONCURRENT_BUILDS, slots.availablePermits());
    }

    @Test
    void boundedWhenSet() {
        Semaphore slots = runner(2).buildSlots();
        assertNotNull(slots);
        assertEquals(2, slots.availablePermits());
    }

    @Test
    void nonPositiveCapIsUnbounded() {
        assertNull(runner(0).buildSlots());
    }

    @Test
    void capOfOneSerializesBuildBodies() throws Exception {
        CodeBuildRunner runner = runner(1);
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        Thread first = gatedThread(runner, () -> {
            firstInside.countDown();
            awaitQuietly(releaseFirst);
        });
        Thread second = gatedThread(runner, () -> {
            secondStarted.countDown();
            secondDone.countDown();
        });

        first.start();
        assertTrue(firstInside.await(2, TimeUnit.SECONDS));

        second.start();
        // The lone permit is held by the first body, so the second cannot enter yet.
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        assertTrue(secondDone.await(2, TimeUnit.SECONDS));

        first.join(2000);
        second.join(2000);
    }

    @Test
    void capOfTwoAllowsBuildBodiesToOverlap() throws Exception {
        assertBodiesOverlap(runner(2));
    }

    @Test
    void defaultCapAllowsBuildBodiesToOverlap() throws Exception {
        // The unset-default cap (> 1) still lets independent bodies run concurrently.
        assertBodiesOverlap(runner(null));
    }

    private void assertBodiesOverlap(CodeBuildRunner runner) throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable body = () -> {
            bothInside.countDown();
            awaitQuietly(release);
        };
        Thread a = gatedThread(runner, body);
        Thread b = gatedThread(runner, body);

        a.start();
        b.start();
        assertTrue(bothInside.await(2, TimeUnit.SECONDS), "both build bodies should run concurrently");

        release.countDown();
        a.join(2000);
        b.join(2000);
    }

    private static Thread gatedThread(CodeBuildRunner runner, Runnable body) {
        Thread thread = new Thread(() -> {
            try {
                runner.withBuildSlot(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
