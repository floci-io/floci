package io.github.hectorvent.floci.services.neptune.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NeptuneContainerManagerTest {

    @Test
    void stopByClusterIdRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        NeptuneContainerManager manager = new NeptuneContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class));

        // No container was ever registered for this id (e.g. it failed before registration).
        // Rollback must still fall back to the deterministic name so nothing is orphaned.
        manager.stopByClusterId("my-cluster");

        verify(lifecycleManager).removeIfExists("floci-neptune-my-cluster");
    }
}
