package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerLivenessTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private EmulatorConfig config;

    @Mock
    private InspectContainerCmd inspectCmd;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config);
        when(dockerClient.inspectContainerCmd("c1")).thenReturn(inspectCmd);
    }

    private InspectContainerResponse inspectResponse(boolean running) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(response.getState()).thenReturn(state);
        return response;
    }

    @Test
    void probeContainerReportsRunning() {
        InspectContainerResponse response = inspectResponse(true);
        when(inspectCmd.exec()).thenReturn(response);

        assertEquals(ContainerLiveness.RUNNING, manager.probeContainer("c1"));
        assertTrue(manager.isContainerRunning("c1"));
    }

    @Test
    void probeContainerReportsStoppedContainerAsNotRunning() {
        InspectContainerResponse response = inspectResponse(false);
        when(inspectCmd.exec()).thenReturn(response);

        assertEquals(ContainerLiveness.NOT_RUNNING, manager.probeContainer("c1"));
        assertFalse(manager.isContainerRunning("c1"));
    }

    @Test
    void probeContainerReportsMissingContainerAsNotRunning() {
        when(inspectCmd.exec()).thenThrow(new NotFoundException("No such container"));

        assertEquals(ContainerLiveness.NOT_RUNNING, manager.probeContainer("c1"));
    }

    @Test
    void probeContainerReportsIoFailureAsUnknownNotStopped() {
        when(inspectCmd.exec()).thenThrow(new UncheckedIOException(new IOException("Broken pipe")));

        assertEquals(ContainerLiveness.UNKNOWN, manager.probeContainer("c1"));
    }

    @Test
    void isContainerRunningStillTreatsProbeFailureAsNotRunningForWarmPoolCallers() {
        when(inspectCmd.exec()).thenThrow(new UncheckedIOException(new IOException("Broken pipe")));

        assertFalse(manager.isContainerRunning("c1"));
    }
}
