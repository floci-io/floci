package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftSnapshotSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void tickIsSkippedBetweenBeforeResetAndAfterReset() {
        RedshiftService service = mock(RedshiftService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshift().snapshotSweepIntervalSeconds()).thenReturn(60L);
        RedshiftSnapshotScheduler scheduler = new RedshiftSnapshotScheduler(service, config);

        scheduler.beforeReset();
        scheduler.tick(NOW);
        verify(service, never()).runSnapshotSchedules(any());

        scheduler.afterReset();
        scheduler.tick(NOW);
        verify(service).runSnapshotSchedules(NOW);
    }
}
