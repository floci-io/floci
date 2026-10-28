package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesCmd;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

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
    private EmulatorConfig config;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, config);
    }

    @Test
    void ensureVolumePersistsOwnershipLabelsAndTheirLegacyAliases() {
        InspectVolumeCmd inspect = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("task-volume")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        CreateVolumeCmd create = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(create);
        manager.ensureVolume("task-volume", Map.of("io.floci.service", "ecs",
                "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "run-id"));
        verify(create).withLabels(argThat(labels -> "ecs".equals(labels.get("io.floci.service"))
                && "alpha/4566".equals(labels.get("io.floci.owner"))
                && "alpha/4566".equals(labels.get("floci_owner_port"))
                && "run-id".equals(labels.get("io.floci.ecs.run"))
                && "run-id".equals(labels.get("floci.ecs-run"))));
    }

    @Test
    void stopAndRemoveStopsContainerBeforeClosingItsLogStream() {
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        List<String> operations = new ArrayList<>();
        doAnswer(invocation -> {
            operations.add("stop");
            return null;
        }).when(stop).exec();
        doAnswer(invocation -> {
            operations.add("remove");
            return null;
        }).when(remove).exec();
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);

        manager.stopAndRemove("container-id", () -> operations.add("logs"));

        assertEquals(List.of("stop", "remove", "logs"), operations);
    }

    @Test
    void stopAndRemoveForceRemovesAndFinalizesLogStreamWhenContainerStopFails() {
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        List<String> operations = new ArrayList<>();
        doThrow(new RuntimeException("Docker daemon unavailable")).when(stop).exec();
        doAnswer(invocation -> {
            operations.add("remove");
            return null;
        }).when(remove).exec();
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);

        manager.stopAndRemove("container-id", () -> operations.add("logs"));

        assertEquals(List.of("remove", "logs"), operations);
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
    void strictContainerCleanupStopsContainerBeforeClosingItsLogStream() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        List<String> operations = new ArrayList<>();
        doAnswer(invocation -> {
            operations.add("stop");
            return null;
        }).when(stop).exec();
        doAnswer(invocation -> {
            operations.add("remove");
            return null;
        }).when(remove).exec();
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);

        manager.stopAndRemoveStrict("container-id", () -> operations.add("logs"));

        assertEquals(List.of("stop", "remove", "logs"), operations);
        verify(stop).withTimeout(5);
    }

    @Test
    void strictContainerCleanupUsesTheCallersStopTimeout() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);

        manager.stopAndRemoveStrict("container-id", null, 2);

        verify(stop).withTimeout(2);
        verify(remove).exec();
    }

    @Test
    void strictContainerCleanupClosesTheLogStreamOfAnAlreadyMissingContainer() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.exec()).thenThrow(new NotFoundException("gone"));
        List<String> operations = new ArrayList<>();

        manager.stopAndRemoveStrict("container-id", () -> operations.add("logs"));

        assertEquals(List.of("logs"), operations);
    }

    @Test
    void strictContainerCleanupLeavesTheLogStreamOpenWhenDockerCouldNotStopOrRemoveTheContainer() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.exec()).thenThrow(new DockerException("stop failed", 500));
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);
        when(remove.exec()).thenThrow(new DockerException("remove failed", 500));
        List<String> operations = new ArrayList<>();

        assertThrows(IllegalStateException.class, () ->
                manager.stopAndRemoveStrict("container-id", () -> operations.add("logs")));

        assertEquals(List.of(), operations);
    }

    @Test
    void strictContainerCleanupLeavesTheLogStreamOpenWhenDockerStoppedButCouldNotRemoveTheContainer() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);
        when(remove.exec()).thenThrow(new DockerException("remove failed", 500));
        List<String> operations = new ArrayList<>();

        assertThrows(IllegalStateException.class, () ->
                manager.stopAndRemoveStrict("container-id", () -> operations.add("logs")));

        assertEquals(List.of(), operations);
    }

    @Test
    void strictContainerCleanupPropagatesRemovalFailure() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);
        when(remove.exec()).thenThrow(new DockerException("remove failed", 500));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                manager.stopAndRemoveStrict("container-id", null));

        assertEquals("Failed to remove container container-id", exception.getMessage());
    }

    @Test
    void strictContainerCleanupAcceptsSuccessfulForcedRemovalAfterStopFailure() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.exec()).thenThrow(new DockerException("stop failed", 500));
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);

        assertDoesNotThrow(() -> manager.stopAndRemoveStrict("container-id", null));

        verify(remove).exec();
    }

    @Test
    void strictContainerCleanupClosesTheLogStreamOnceForcedRemovalSucceedsAfterStopFailure() throws Exception {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        Closeable logStream = mock(Closeable.class);
        when(dockerClient.stopContainerCmd("container-id")).thenReturn(stop);
        when(stop.exec()).thenThrow(new DockerException("stop failed", 500));
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(remove);

        manager.stopAndRemoveStrict("container-id", logStream);

        verify(logStream).close();
    }

    @Test
    void strictStaleContainerCleanupPropagatesRemovalFailure() {
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("stale-name")).thenReturn(remove);
        when(remove.exec()).thenThrow(new DockerException("remove failed", 500));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                manager.removeIfExistsStrict("stale-name"));

        assertEquals("Failed to remove stale container stale-name", exception.getMessage());
    }

    @Test
    void strictStaleContainerCleanupAcceptsMissingContainer() {
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("stale-name")).thenReturn(remove);
        when(remove.exec()).thenThrow(new NotFoundException("missing"));

        assertDoesNotThrow(() -> manager.removeIfExistsStrict("stale-name"));
    }

    @Test
    void strictVolumeCleanupPropagatesRemovalFailure() {
        RemoveVolumeCmd remove = mock(RemoveVolumeCmd.class);
        when(dockerClient.removeVolumeCmd("volume-id")).thenReturn(remove);
        when(remove.exec()).thenThrow(new DockerException("remove failed", 500));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                manager.removeVolumeStrict("volume-id"));

        assertEquals("Failed to remove volume volume-id", exception.getMessage());
    }

    @Test
    void tryListVolumeNamesReturnsAuthoritativeSnapshot() {
        ListVolumesCmd cmd = mock(ListVolumesCmd.class);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        InspectVolumeResponse first = mock(InspectVolumeResponse.class);
        InspectVolumeResponse second = mock(InspectVolumeResponse.class);
        when(dockerClient.listVolumesCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(response);
        when(response.getVolumes()).thenReturn(List.of(first, second));
        when(first.getName()).thenReturn("floci-code-first");
        when(second.getName()).thenReturn("shared-data");

        assertEquals(Optional.of(Set.of("floci-code-first", "shared-data")),
                manager.tryListVolumeNames());
    }

    @Test
    void tryListVolumeNamesDistinguishesEmptyInventory() {
        ListVolumesCmd cmd = mock(ListVolumesCmd.class);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        when(dockerClient.listVolumesCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(response);
        when(response.getVolumes()).thenReturn(List.of());

        assertEquals(Optional.of(Set.of()), manager.tryListVolumeNames());
    }

    @Test
    void tryListVolumeNamesReturnsEmptyOptionalForNullResponse() {
        ListVolumesCmd cmd = mock(ListVolumesCmd.class);
        when(dockerClient.listVolumesCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(null);

        assertEquals(Optional.empty(), manager.tryListVolumeNames());
    }

    @Test
    void tryListVolumeNamesReturnsEmptyOptionalForNullVolumeList() {
        ListVolumesCmd cmd = mock(ListVolumesCmd.class);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        when(dockerClient.listVolumesCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(response);
        when(response.getVolumes()).thenReturn(null);

        assertEquals(Optional.empty(), manager.tryListVolumeNames());
    }

    @Test
    void tryListVolumeNamesReturnsEmptyOptionalOnDockerFailure() {
        ListVolumesCmd cmd = mock(ListVolumesCmd.class);
        when(dockerClient.listVolumesCmd()).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new DockerException("Connection refused", 500));

        assertEquals(Optional.empty(), manager.tryListVolumeNames());
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
    void ensureSharedVolume_volumeRecreated_reinitialisesOwnership() {
        // The volume exists on the first launch, then is removed (e.g. DeleteApplication in memory
        // mode) and recreated root:root on the next one: the run-once guard must not skip the chown.
        InspectVolumeCmd ivc = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(ivc);
        when(ivc.exec())
                .thenReturn(mock(InspectVolumeResponse.class))
                .thenThrow(new NotFoundException("No such volume"));

        CreateVolumeCmd cvc = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(cvc);

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

        manager.ensureSharedVolume("shared", OptionalInt.of(9999), OptionalInt.of(9999),
                Optional.empty(), "busybox:stable");
        manager.ensureSharedVolume("shared", OptionalInt.of(9999), OptionalInt.of(9999),
                Optional.empty(), "busybox:stable");

        verify(dockerClient, times(1)).createVolumeCmd();
        verify(dockerClient, times(2)).createContainerCmd("busybox:stable");
        verify(ccc, times(2)).withCmd("sh", "-c", "chown 9999:9999 /floci-shared-volume && true");
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
    void isValidPermissions_matchesThreeOrFourOctalDigits() {
        assertTrue(ContainerLifecycleManager.isValidPermissions("755"));
        assertTrue(ContainerLifecycleManager.isValidPermissions("0755"));
        assertTrue(ContainerLifecycleManager.isValidPermissions("2775"));
        assertFalse(ContainerLifecycleManager.isValidPermissions(null));
        assertFalse(ContainerLifecycleManager.isValidPermissions(""));
        assertFalse(ContainerLifecycleManager.isValidPermissions("888"));
        assertFalse(ContainerLifecycleManager.isValidPermissions("07777"));
        assertFalse(ContainerLifecycleManager.isValidPermissions("rwxr-xr-x"));
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

    /** Stubs {@code inspectVolumeCmd("vol").exec()} to defer to {@code behaviour}, counting calls. */
    private AtomicInteger inspectVolumeBehaving(IntFunction<InspectVolumeResponse> behaviour) {
        AtomicInteger calls = new AtomicInteger();
        InspectVolumeCmd cmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("vol")).thenReturn(cmd);
        when(cmd.exec()).thenAnswer(inv -> behaviour.apply(calls.incrementAndGet()));
        return calls;
    }

    /** Stubs {@code createVolumeCmd()...exec()} to defer to {@code behaviour}, counting calls. */
    private AtomicInteger createVolumeBehaving(IntConsumer behaviour) {
        AtomicInteger calls = new AtomicInteger();
        CreateVolumeCmd cmd = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(cmd);
        when(cmd.exec()).thenAnswer(inv -> {
            behaviour.accept(calls.incrementAndGet());
            return mock(CreateVolumeResponse.class);
        });
        return calls;
    }

    @Test
    void ensureVolume_createsMissingVolumeExactlyOnce() {
        AtomicInteger inspects = inspectVolumeBehaving(attempt -> {
            throw new NotFoundException("No such volume: vol");
        });
        AtomicInteger creates = createVolumeBehaving(attempt -> {
        });

        manager.ensureVolume("vol");

        assertEquals(1, inspects.get());
        assertEquals(1, creates.get(), "a missing volume must be created");
    }

    /** A Floci-derived volume carries the labels a floci-scoped prune and sweep filter on. */
    @Test
    void ensureVolume_labelsCreatedVolumeAsFlociManaged() {
        inspectVolumeBehaving(attempt -> {
            throw new NotFoundException("No such volume: vol");
        });
        CreateVolumeCmd cmd = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(CreateVolumeResponse.class));
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);

        manager.ensureVolume("vol");

        verify(cmd).withLabels(labels.capture());
        assertEquals(Map.of("floci", "true", "floci_emulator", "floci-aws"), labels.getValue());
    }

    /**
     * A volume an operator named is created without any Floci label: no sweep of
     * Floci-labelled volumes, a floci-scoped prune or a testcontainers run's
     * labelled-children reap, may collect operator storage.
     */
    @Test
    void ensureUnmanagedVolume_createsMissingVolumeWithoutLabels() {
        AtomicInteger inspects = inspectVolumeBehaving(attempt -> {
            throw new NotFoundException("No such volume: vol");
        });
        CreateVolumeCmd cmd = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(mock(CreateVolumeResponse.class));
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);

        manager.ensureUnmanagedVolume("vol");

        assertEquals(1, inspects.get());
        verify(cmd).withLabels(labels.capture());
        assertTrue(labels.getValue().isEmpty(),
                "an operator-named volume carries no label a Floci sweep filters on");
    }

    /** An existing operator-named volume is left exactly as the operator created it. */
    @Test
    void ensureUnmanagedVolume_doesNotCreateWhenVolumeAlreadyExists() {
        AtomicInteger inspects = inspectVolumeBehaving(attempt ->
                mock(InspectVolumeResponse.class));

        manager.ensureUnmanagedVolume("vol");

        assertEquals(1, inspects.get());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void ensureVolume_doesNotCreateWhenVolumeAlreadyExists() {
        // This existence guard is what keeps ensureVolume idempotent now that transient-I/O
        // retries live at the transport: when the daemon created the volume but the response was
        // lost to a broken pipe, the transport's replayed create finds the volume already there,
        // POST /volumes/create with the same name is itself idempotent, and later ensureVolume
        // calls see it exists and do nothing.
        AtomicInteger inspects = inspectVolumeBehaving(attempt ->
                mock(InspectVolumeResponse.class));

        manager.ensureVolume("vol");

        assertEquals(1, inspects.get());
        verify(dockerClient, never()).createVolumeCmd();
    }

    @Test
    void ensureVolume_doesNotRetryOnTopOfTheTransport() {
        inspectVolumeBehaving(attempt -> {
            throw new NotFoundException("No such volume: vol");
        });
        RuntimeException transportGaveUp = new RuntimeException(new IOException("Broken pipe"));
        AtomicInteger creates = createVolumeBehaving(attempt -> {
            throw transportGaveUp;
        });

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> manager.ensureVolume("vol"));

        assertSame(transportGaveUp, thrown,
                "a transient error surfacing here means the transport already spent the retry"
                        + " budget; it must surface unchanged");
        assertEquals(1, creates.get(),
                "no call-site loop: an outer retry would compound backoff on an exhausted"
                        + " inner one");
    }

    @Test
    void ensureVolume_doesNotSwallowDaemonRejection() {
        inspectVolumeBehaving(attempt -> {
            throw new NotFoundException("No such volume: vol");
        });
        DockerException rejected = new DockerException("volume name already in use", 409);
        AtomicInteger creates = createVolumeBehaving(attempt -> {
            throw rejected;
        });

        DockerException thrown =
                assertThrows(DockerException.class, () -> manager.ensureVolume("vol"));

        assertSame(rejected, thrown, "a genuine daemon rejection must surface unchanged");
        assertEquals(1, creates.get(), "a name conflict never clears on a retry");
    }

    @Test
    void ensureSharedVolume_startsHelperThroughTheTranslatingStartContainer() {
        // github.com/floci-io/floci/issues/2243 (follow-up, PR #2797 review): the shared-volume
        // init helper is a third container-start call site, distinct from createAndStart /
        // startCreated and from adopt(). A failure here is caught as a plain RuntimeException by
        // ensureSharedVolume's computeIfAbsent and only logged (never rethrown to the caller), so
        // whether the logged message is the misleading raw "Disk quota exceeded" or the translated
        // one depends entirely on whether this call site goes through startContainer(). Verifying
        // startContainer("helper-id") is actually invoked (via a spy) is the only way to pin that
        // routing, since the swallowed exception itself is not observable from ensureSharedVolume.
        ContainerLifecycleManager spyManager = spy(manager);
        doNothing().when(spyManager).startContainer("helper-id");

        InspectVolumeCmd ivc = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("shared")).thenReturn(ivc);
        when(ivc.exec()).thenReturn(mock(InspectVolumeResponse.class));

        CreateContainerCmd ccc = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(ccc);
        CreateContainerResponse resp = mock(CreateContainerResponse.class);
        when(resp.getId()).thenReturn("helper-id");
        when(ccc.exec()).thenReturn(resp);

        WaitContainerCmd wcc = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("helper-id")).thenReturn(wcc);
        WaitContainerResultCallback wcb = mock(WaitContainerResultCallback.class);
        when(wcc.exec(any(WaitContainerResultCallback.class))).thenReturn(wcb);
        when(wcb.awaitStatusCode(anyLong(), any())).thenReturn(0);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));

        spyManager.ensureSharedVolume("shared", OptionalInt.of(1001), OptionalInt.of(1001),
                Optional.of("2775"), "busybox:stable");

        verify(spyManager).startContainer("helper-id");
        verify(dockerClient, never()).startContainerCmd("helper-id");
    }

    @Test
    void ensureBindDirectoryRunsRootHelperUsingImageOwnershipAndCleansUp() {
        CreateContainerCmd command = bindInitHelper(0);
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);

        manager.ensureBindDirectory("/host/domain", "custom-opensearch", "/usr/share/opensearch/data");

        verify(imageCacheService).ensureImageExists("custom-opensearch");
        verify(command).withHostConfig(hostConfig.capture());
        assertEquals("/host/domain", hostConfig.getValue().getBinds()[0].getPath());
        assertEquals("/floci-bind-directory", hostConfig.getValue().getBinds()[0].getVolume().getPath());
        verify(command).withUser("0");
        verify(command).withEntrypoint("sh");
        verify(command).withCmd("-c", "ownership=$(stat -c '%u:%g' \"$1\") && "
                + "chown \"$ownership\" \"$2\" && chmod u+rwx \"$2\"",
                "floci-bind-init", "/usr/share/opensearch/data", "/floci-bind-directory");
        verify(dockerClient).startContainerCmd("helper-id");
        verify(dockerClient).removeContainerCmd("helper-id");
    }

    @Test
    void ensureBindDirectoryPropagatesFailedInitAndStillRemovesHelper() {
        bindInitHelper(1);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                manager.ensureBindDirectory("/host/domain", "custom-opensearch", "/usr/share/opensearch/data"));

        assertTrue(exception.getMessage().contains("exited with status 1"));
        verify(dockerClient).removeContainerCmd("helper-id");
    }

    @Test
    void ensureBindDirectoryRechecksDirectoryOnEveryLaunch() {
        bindInitHelper(0);

        manager.ensureBindDirectory("/host/domain", "custom-opensearch", "/usr/share/opensearch/data");
        manager.ensureBindDirectory("/host/domain", "custom-opensearch", "/usr/share/opensearch/data");

        verify(dockerClient, times(2)).createContainerCmd("custom-opensearch");
    }

    private CreateContainerCmd bindInitHelper(int status) {
        CreateContainerCmd command = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("custom-opensearch")).thenReturn(command);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("helper-id");
        when(command.exec()).thenReturn(response);
        when(dockerClient.startContainerCmd("helper-id")).thenReturn(mock(StartContainerCmd.class));
        WaitContainerCmd wait = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("helper-id")).thenReturn(wait);
        WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
        when(wait.exec(any(WaitContainerResultCallback.class))).thenReturn(callback);
        when(callback.awaitStatusCode(60, TimeUnit.SECONDS)).thenReturn(status);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));
        return command;
    }
}
