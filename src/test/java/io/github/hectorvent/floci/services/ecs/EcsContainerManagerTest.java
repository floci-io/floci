package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EcsContainerManagerTest {

    @Mock ContainerBuilder containerBuilder;
    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerLogStreamer logStreamer;
    @Mock ContainerDetector containerDetector;
    @Mock EmulatorConfig config;
    @Mock RegionResolver regionResolver;
    @Mock DockerClient dockerClient;
    @Mock InspectContainerCmd inspectContainerCmd;

    EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, logStreamer, containerDetector, config, regionResolver);
    }

    @Test
    void checkContainerExitStatus_runningContainer_returnsNull() {
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123")).thenReturn(inspectContainerCmd);

        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(true);

        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getState()).thenReturn(state);
        when(inspectContainerCmd.exec()).thenReturn(response);

        assertNull(manager.checkContainerExitStatus("abc123"));
    }

    @Test
    void checkContainerExitStatus_exitedContainer_returnsExitStatus() {
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123")).thenReturn(inspectContainerCmd);

        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(false);
        when(state.getExitCode()).thenReturn(0);
        when(state.getFinishedAt()).thenReturn("2026-01-01T00:00:00Z");

        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getState()).thenReturn(state);
        when(inspectContainerCmd.exec()).thenReturn(response);

        var exitStatus = manager.checkContainerExitStatus("abc123");

        assertNotNull(exitStatus);
        assertEquals(0, exitStatus.exitCode());
        assertEquals("2026-01-01T00:00:00Z", exitStatus.finishedAt());
    }

    @Test
    void checkContainerExitStatus_nonExistentContainer_returnsNull() {
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("nonexistent")).thenReturn(inspectContainerCmd);
        when(inspectContainerCmd.exec()).thenThrow(new RuntimeException("not found"));

        assertNull(manager.checkContainerExitStatus("nonexistent"));
    }

    @Test
    void checkContainerExitStatus_nullDockerId_returnsNull() {
        assertNull(manager.checkContainerExitStatus(null));
    }
}