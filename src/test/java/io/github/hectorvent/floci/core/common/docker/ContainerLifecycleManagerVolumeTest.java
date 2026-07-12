package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerVolumeTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, portAllocator);
    }

    @Test
    void volumeExists_returnsTrue_whenVolumeExists() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("my-volume")).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(InspectVolumeResponse.class));

        assertTrue(manager.volumeExists("my-volume"));
    }

    @Test
    void volumeExists_returnsFalse_whenVolumeNotFound() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("nonexistent")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new NotFoundException("No such volume"));

        assertFalse(manager.volumeExists("nonexistent"));
    }

    @Test
    void volumeExists_returnsFalse_forNullName() {
        assertFalse(manager.volumeExists(null));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_forBlankName() {
        assertFalse(manager.volumeExists("  "));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_forAbsolutePath() {
        assertFalse(manager.volumeExists("/var/lib/data"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_forRelativePath() {
        assertFalse(manager.volumeExists("./data"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_forWindowsAbsolutePathBackslash() {
        assertFalse(manager.volumeExists("C:\\Users\\data"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_forWindowsAbsolutePathForwardSlash() {
        assertFalse(manager.volumeExists("D:/sources/data"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void volumeExists_returnsFalse_onDockerException() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("some-volume")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new DockerException("Connection refused", 500));

        assertFalse(manager.volumeExists("some-volume"));
    }

    @Test
    void ensureSharedVolume_noOwnershipConfig_createsVolumeButNoHelperContainer() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(InspectVolumeResponse.class)); // volume already exists

        manager.ensureSharedVolume("shared", OptionalInt.empty(), OptionalInt.empty(),
                Optional.empty(), "busybox:stable");

        // No ownership requested -> degrades to a plain named volume: no helper container is
        // spun up and no init image is pulled (proves the clean-source default is a no-op).
        verify(dockerClient, never()).createContainerCmd(anyString());
        verifyNoInteractions(imageCacheService);
    }

    @Test
    void ensureSharedVolume_withOwnership_runsChownChmodHelperExactlyOnce() {
        InspectVolumeCmd ivc = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(ivc);
        when(ivc.exec()).thenReturn(mock(InspectVolumeResponse.class));

        CreateContainerCmd ccc = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(ccc);
        CreateContainerResponse resp = mock(CreateContainerResponse.class);
        when(resp.getId()).thenReturn("helper-id");
        when(ccc.exec()).thenReturn(resp);

        StartContainerCmd scc = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("helper-id")).thenReturn(scc);

        WaitContainerCmd wcc = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("helper-id")).thenReturn(wcc);
        WaitContainerResultCallback wcb = mock(WaitContainerResultCallback.class);
        when(wcc.exec(any(WaitContainerResultCallback.class))).thenReturn(wcb);
        when(wcb.awaitStatusCode(anyLong(), any())).thenReturn(0);

        RemoveContainerCmd rcc = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(rcc);

        manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                Optional.of("2775"), "busybox:stable");
        // Second call is a no-op thanks to the run-once guard.
        manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                Optional.of("2775"), "busybox:stable");

        verify(imageCacheService, times(1)).ensureImageExists("busybox:stable");
        // The setgid bit is carried by the 4-digit octal (2775) rather than a separate chmod g+s.
        verify(ccc, times(1)).withCmd("sh", "-c",
                "chown 1001:1001 /floci-shared-volume && chmod 2775 /floci-shared-volume && true");
        verify(dockerClient, times(1)).createContainerCmd("busybox:stable");
        verify(dockerClient, times(1)).removeContainerCmd("helper-id");
    }

    @Test
    void ensureSharedVolume_partialOwnershipConfig_isRejected() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(InspectVolumeResponse.class));

        // owner-uid without owner-gid is not a valid CreationInfo — reject rather than emit
        // a malformed `chown 1001:` that fails in busybox.
        assertThrows(IllegalArgumentException.class, () ->
                manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.empty(),
                        Optional.empty(), "busybox:stable"));
        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void ensureSharedVolume_invalidRootPermissions_isRejected() {
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(InspectVolumeResponse.class));

        // Non-octal permissions must be rejected before being spliced into the helper's sh -c.
        assertThrows(IllegalArgumentException.class, () ->
                manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                        Optional.of("999"), "busybox:stable"));
        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void ensureSharedVolume_helperNonZeroExit_retriesOnNextLaunch() {
        InspectVolumeCmd ivc = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(ivc);
        when(ivc.exec()).thenReturn(mock(InspectVolumeResponse.class));

        CreateContainerCmd ccc = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(ccc);
        CreateContainerResponse resp = mock(CreateContainerResponse.class);
        when(resp.getId()).thenReturn("helper-id");
        when(ccc.exec()).thenReturn(resp);
        when(dockerClient.startContainerCmd("helper-id")).thenReturn(mock(StartContainerCmd.class));
        WaitContainerCmd wcc = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("helper-id")).thenReturn(wcc);
        WaitContainerResultCallback wcb = mock(WaitContainerResultCallback.class);
        when(wcc.exec(any(WaitContainerResultCallback.class))).thenReturn(wcb);
        when(wcb.awaitStatusCode(anyLong(), any())).thenReturn(1); // helper fails
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));

        // A non-zero helper exit must not memoise the volume as initialised — the next launch
        // retries rather than leaving the root root:root 0755 forever.
        manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                Optional.of("0777"), "busybox:stable");
        manager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                Optional.of("0777"), "busybox:stable");

        verify(dockerClient, times(2)).createContainerCmd("busybox:stable");
    }
}

