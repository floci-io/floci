package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ContainerLifecycleManagerEphemeralTest {
    private DockerClient docker;
    private ContainerLifecycleManager manager;
    private RemoveContainerCmd remove;
    private CreateContainerCmd create;
    private final ContainerSpec spec = new ContainerSpec("postgres:16-alpine");

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        ImageCacheService images = mock(ImageCacheService.class);
        when(images.ensureImageExists(spec.image())).thenReturn("image-id");
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        manager = spy(new ContainerLifecycleManager(docker, images, mock(ContainerDetector.class), config));
        create = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("owned-id");
        when(create.exec()).thenReturn(response);
        when(docker.createContainerCmd("image-id")).thenReturn(create);
        remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(anyString())).thenReturn(remove);
    }

    @Test
    void failedEphemeralStartRemovesAnonymousVolumes() {
        IllegalStateException failure = new IllegalStateException("start failed");
        doThrow(failure).when(manager).startCreated("owned-id", spec);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.createAndStartEphemeral(spec)));
        verify(remove).withRemoveVolumes(true);
        verify(remove).exec();
    }

    @Test
    void failedRemovalPreservesStartFailureAndSuppressedCleanupCause() {
        IllegalStateException failure = new IllegalStateException("start failed");
        IllegalStateException cleanup = new IllegalStateException("daemon unavailable");
        doThrow(failure).when(manager).startCreated("owned-id", spec);
        when(remove.exec()).thenThrow(cleanup);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> manager.createAndStartEphemeral(spec)));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(cleanup, failure.getSuppressed()[0].getCause());
    }

    @Test
    void failedCreateDoesNotRemoveUnownedContainer() {
        when(create.exec()).thenThrow(new IllegalStateException("create failed"));
        assertThrows(IllegalStateException.class, () -> manager.createAndStartEphemeral(spec));
        verify(docker, never()).removeContainerCmd(anyString());
    }

    @Test
    void successfulEphemeralStartKeepsItsVolume() {
        ContainerLifecycleManager.ContainerInfo info = new ContainerLifecycleManager.ContainerInfo("owned-id", Map.of());
        doReturn(info).when(manager).startCreated("owned-id", spec);
        assertSame(info, manager.createAndStartEphemeral(spec));
        verify(docker, never()).removeContainerCmd(anyString());
    }

    @Test
    void defaultStrictRemovalDoesNotOptIntoVolumeRemoval() {
        manager.removeIfExistsStrict("owned-id");
        verify(remove, never()).withRemoveVolumes(true);
        verify(remove).exec();
    }

    @Test
    void missingEphemeralContainerIsAlreadyClean() {
        when(remove.exec()).thenThrow(new NotFoundException("gone"));
        assertDoesNotThrow(() -> manager.removeIfExistsStrict("owned-id", true));
        verify(remove).withRemoveVolumes(true);
    }

    @Test
    void strictEphemeralRemovalExposesFailure() {
        IllegalStateException failure = new IllegalStateException("daemon unavailable");
        when(remove.exec()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> manager.removeIfExistsStrict("owned-id", true)).getCause());
    }
}
