package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.testutil.ShutdownOrdering;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotCopySchedulerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final RedshiftServerlessSnapshotCopyService copies = mock(RedshiftServerlessSnapshotCopyService.class);

    private RedshiftServerlessSnapshotCopyScheduler scheduler(boolean serviceEnabled, boolean copyEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftServerless().enabled()).thenReturn(serviceEnabled);
        when(config.services().redshiftServerless().snapshotCopyEnabled()).thenReturn(copyEnabled);
        when(config.services().redshiftServerless().snapshotCopySweepIntervalSeconds()).thenReturn(60);
        return new RedshiftServerlessSnapshotCopyScheduler(copies, config);
    }

    @Test
    void workerExistsOnlyWhenTheServiceAndTheSwitchAreBothOn() {
        assertTrue(scheduler(true, true).active());
        assertFalse(scheduler(true, false).active());
        assertFalse(scheduler(false, true).active());
    }

    @Test
    void shutdownObserverRunsBeforeStorageIsFlushedAndClosed() throws Exception {
        ShutdownOrdering.assertStopsBeforeStorageCloses(RedshiftServerlessSnapshotCopyScheduler.class);
    }

    @Test
    void tickRunsTheCopySweepAtTheGivenInstant() {
        scheduler(true, false).tick(NOW);
        verify(copies).runCopySweep(NOW);
    }

    @Test
    void tickIsSkippedBetweenBeforeResetAndAfterReset() {
        RedshiftServerlessSnapshotCopyScheduler scheduler = scheduler(true, false);
        scheduler.beforeReset();
        scheduler.tick(NOW);
        verify(copies, never()).runCopySweep(any());

        scheduler.afterReset();
        scheduler.tick(NOW);
        verify(copies).runCopySweep(eq(NOW));
    }
}
