package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** A run's container is removed by whoever claims it first, the run itself or a stop, and by no one else. */
class ContainerLaunchesTest {

    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerLaunches launches = new ContainerLaunches();

    @Test
    void aStopRemovesAStartedContainerAndLeavesTheRunNothingToRemove() {
        ContainerLaunches.Launch launch = launches.begin("run-1");
        launch.started("container-1");

        launches.stopAll(lifecycle, 0);

        verify(lifecycle).stopAndRemove("container-1", null, 0);
        assertTrue(launch.stopRequested());
        assertNull(launches.end("run-1", launch));
        assertNull(launches.get("run-1"));
    }

    @Test
    void aStopBeforeTheContainerIsUpOnlyAsksTheRunToStop() {
        ContainerLaunches.Launch launch = launches.begin("run-1");

        launches.get("run-1").stopNow(lifecycle, 0);
        launch.started("container-1");

        verifyNoInteractions(lifecycle);
        assertTrue(launch.stopRequested());
        assertEquals("container-1", launches.end("run-1", launch));
    }

    @Test
    void aRequestedStopRemovesNothing() {
        ContainerLaunches.Launch launch = launches.begin("run-1");
        launch.started("container-1");

        launch.requestStop();

        verify(lifecycle, never()).stopAndRemove("container-1", null, 0);
        assertEquals("container-1", launch.container());
        assertEquals("container-1", launches.end("run-1", launch));
    }
}
