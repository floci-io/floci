package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.testutil.ShutdownOrdering;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotExpirationSchedulerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final RedshiftServerlessService service = mock(RedshiftServerlessService.class);

    private RedshiftServerlessSnapshotExpirationScheduler scheduler(boolean serviceEnabled, boolean actionsEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftServerless().enabled()).thenReturn(serviceEnabled);
        when(config.services().redshiftServerless().snapshotExpirationEnabled()).thenReturn(actionsEnabled);
        when(config.services().redshiftServerless().snapshotExpirationSweepIntervalSeconds()).thenReturn(60);
        return new RedshiftServerlessSnapshotExpirationScheduler(service, mock(RedshiftServerlessResourcePolicyService.class), config);
    }

    @Test
    void workerExistsOnlyWhenTheServiceAndTheSwitchAreBothOn() {
        assertTrue(scheduler(true, true).active());
        assertFalse(scheduler(true, false).active());
        assertFalse(scheduler(false, true).active());
    }

    @Test
    void shutdownObserverRunsBeforeStorageIsFlushedAndClosed() throws Exception {
        ShutdownOrdering.assertStopsBeforeStorageCloses(RedshiftServerlessSnapshotExpirationScheduler.class);
    }

    @Test
    void tickRunsTheSweepAtTheGivenInstant() {
        scheduler(true, false).tick(NOW);
        verify(service).expireSnapshots(eq(NOW), any());
    }

    @Test
    void tickIsSkippedBetweenBeforeResetAndAfterReset() {
        RedshiftServerlessSnapshotExpirationScheduler scheduler = scheduler(true, false);
        scheduler.beforeReset();
        scheduler.tick(NOW);
        verify(service, never()).expireSnapshots(any(), any());

        scheduler.afterReset();
        scheduler.tick(NOW);
        verify(service).expireSnapshots(eq(NOW), any());
    }
}
