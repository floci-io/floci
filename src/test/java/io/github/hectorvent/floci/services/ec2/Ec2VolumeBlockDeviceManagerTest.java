package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers how the volume manager drives its helper container without a Docker daemon: the command
 * it runs there, and that a Docker failure while running it is reported rather than thrown.
 */
class Ec2VolumeBlockDeviceManagerTest {

    private static final String HELPER_ID = "helper-container-id";

    private DockerClient dockerClient;
    private Ec2VolumeBlockDeviceManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        InspectContainerResponse helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getState().getRunning()).thenReturn(true);
        when(helper.getId()).thenReturn(HELPER_ID);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenReturn(helper);

        manager = new Ec2VolumeBlockDeviceManager(dockerClient, mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), config);
    }

    @Test
    void createVolumeSizesTheBackingFileInsideTheHelperContainer() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.createVolume("vol-1", 0);

        assertEquals(List.of(List.of("sh", "-c", "truncate -s 8G /volumes/vol-1.raw")), commands);
    }

    @Test
    void aDockerFailureWhileRunningTheCommandIsNotThrown() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> manager.createVolume("vol-1", 8));
    }
}
