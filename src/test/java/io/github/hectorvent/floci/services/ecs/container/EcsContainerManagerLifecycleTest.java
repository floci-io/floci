package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListVolumesCmd;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsContainerManagerLifecycleTest {

    @Test
    void forceRemovalWithUnknownExitCodeDoesNotBecomeSuccessOnRetry() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("docker-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("docker-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);

        EcsContainerManager manager = spy(manager(lifecycleManager));
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());
        doAnswer(ignored -> handle.allContainersRemoved() ? 0 : null)
                .when(manager).getExitCodeIfStopped("docker-id");

        Map<String, Integer> firstAttempt = manager.stopTaskAndCollectExitCodes(handle);
        Map<String, Integer> retry = manager.stopTaskAndCollectExitCodes(handle);

        assertTrue(handle.allContainersRemoved());
        assertTrue(firstAttempt.containsKey("app"));
        assertNull(firstAttempt.get("app"));
        assertNull(retry.get("app"));
        verify(remove, times(1)).exec();
    }

    @Test
    void failedRemovalLeavesTheTaskContainerUnresolvedForRetry() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("docker-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("docker-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        doThrow(new RuntimeException("remove failed")).when(remove).exec();

        EcsContainerManager manager = spy(manager(lifecycleManager));
        doReturn(0).when(manager).getExitCodeIfStopped("docker-id");
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"), Map.of());

        Map<String, Integer> exitCodes = manager.stopTaskAndCollectExitCodes(handle);

        assertTrue(exitCodes.containsKey("app"));
        assertNull(exitCodes.get("app"), "an unremoved container must remain retryable even after it exits");
    }

    @Test
    void retryPreservesExitCodeOfASiblingRemovedOnTheFirstAttempt() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd firstStop = mock(StopContainerCmd.class);
        StopContainerCmd secondStop = mock(StopContainerCmd.class);
        RemoveContainerCmd firstRemove = mock(RemoveContainerCmd.class);
        RemoveContainerCmd secondRemove = mock(RemoveContainerCmd.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("first-id")).thenReturn(firstStop);
        when(dockerClient.stopContainerCmd("second-id")).thenReturn(secondStop);
        when(firstStop.withTimeout(5)).thenReturn(firstStop);
        when(secondStop.withTimeout(5)).thenReturn(secondStop);
        when(dockerClient.removeContainerCmd("first-id")).thenReturn(firstRemove);
        when(dockerClient.removeContainerCmd("second-id")).thenReturn(secondRemove);
        when(firstRemove.withForce(true)).thenReturn(firstRemove);
        when(secondRemove.withForce(true)).thenReturn(secondRemove);
        doThrow(new RuntimeException("remove failed")).doNothing().when(secondRemove).exec();

        EcsContainerManager manager = spy(manager(lifecycleManager));
        doReturn(137).when(manager).getExitCodeIfStopped("first-id");
        doReturn(0).when(manager).getExitCodeIfStopped("second-id");
        EcsTaskHandle handle = new EcsTaskHandle("task-arn",
                Map.of("first", "first-id", "second", "second-id"), Map.of());

        Map<String, Integer> firstAttempt = manager.stopTaskAndCollectExitCodes(handle);
        assertNull(firstAttempt.get("second"));
        doReturn(0).when(manager).getExitCodeIfStopped("first-id");

        Map<String, Integer> retry = manager.stopTaskAndCollectExitCodes(handle);
        assertEquals(137, retry.get("first"));
        assertEquals(0, retry.get("second"));
    }

    @Test
    void finalizesTaskLogStreamsAfterForceRemovingAContainerWhoseStopFails() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        Closeable logStream = mock(Closeable.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("docker-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("docker-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        doThrow(new RuntimeException("Docker daemon unavailable")).when(stop).exec();

        EcsContainerManager manager = manager(lifecycleManager);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"),
                Map.of("docker-id", logStream));

        manager.stopTaskAndCollectExitCodes(handle);

        verify(remove).exec();
        verify(lifecycleManager).closeLogStreamAfterContainerStop(logStream);
    }

    @Test
    void finalizesTaskLogStreamsAfterEveryContainerStops() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        Closeable logStream = mock(Closeable.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("docker-id")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.removeContainerCmd("docker-id")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);

        EcsContainerManager manager = manager(lifecycleManager);
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", Map.of("app", "docker-id"),
                Map.of("docker-id", logStream));

        manager.stopTaskAndCollectExitCodes(handle);

        verify(lifecycleManager).closeLogStreamAfterContainerStop(logStream);
    }

    @Test
    void preservesOnlyTheLogStreamForAContainerThatCouldNotStopOrBeRemoved() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        StopContainerCmd failedStop = mock(StopContainerCmd.class);
        StopContainerCmd successfulStop = mock(StopContainerCmd.class);
        RemoveContainerCmd failedRemove = mock(RemoveContainerCmd.class);
        RemoveContainerCmd successfulRemove = mock(RemoveContainerCmd.class);
        Closeable retainedLogStream = mock(Closeable.class);
        Closeable finalizedLogStream = mock(Closeable.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.stopContainerCmd("running-id")).thenReturn(failedStop);
        when(failedStop.withTimeout(5)).thenReturn(failedStop);
        doThrow(new RuntimeException("stop failed")).when(failedStop).exec();
        when(dockerClient.stopContainerCmd("stopped-id")).thenReturn(successfulStop);
        when(successfulStop.withTimeout(5)).thenReturn(successfulStop);
        when(dockerClient.removeContainerCmd("running-id")).thenReturn(failedRemove);
        when(failedRemove.withForce(true)).thenReturn(failedRemove);
        doThrow(new RuntimeException("remove failed")).when(failedRemove).exec();
        when(dockerClient.removeContainerCmd("stopped-id")).thenReturn(successfulRemove);
        when(successfulRemove.withForce(true)).thenReturn(successfulRemove);

        Map<String, String> containerIds = new LinkedHashMap<>();
        containerIds.put("running", "running-id");
        containerIds.put("stopped", "stopped-id");
        EcsTaskHandle handle = new EcsTaskHandle("task-arn", containerIds,
                Map.of("running-id", retainedLogStream, "stopped-id", finalizedLogStream));

        manager(lifecycleManager).stopTaskAndCollectExitCodes(handle);

        verify(lifecycleManager, never()).closeLogStreamAfterContainerStop(retainedLogStream);
        verify(lifecycleManager).closeLogStreamAfterContainerStop(finalizedLogStream);
        assertTrue(handle.hasOpenLogStreams());
        assertSame(retainedLogStream, handle.getLogStreamsByContainerId().get("running-id"));
        assertFalse(handle.getLogStreamsByContainerId().containsKey("stopped-id"));
    }

    @Test
    void removeLeftoverContainersFindsOwnLeftoversUnderTheNewAndLegacyLabels() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        Map<String, Container> newKeys = Map.of(
                "new-only", leftover("new-only", Map.of(
                        "io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "old-run")),
                "both", leftover("both", Map.of(
                        "io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "old-run",
                        "floci_owner_port", "alpha/4566", "floci.ecs-run", "old-run")),
                "current-run", leftover("current-run", Map.of(
                        "io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "current")));
        Map<String, Container> legacyKeys = Map.of(
                "legacy-only", leftover("legacy-only", Map.of(
                        "io.floci.service", "ecs", "floci_owner_port", "alpha/4566", "floci.ecs-run", "old-run")),
                "both", newKeys.get("both"),
                "legacy-current-run", leftover("legacy-current-run", Map.of(
                        "io.floci.service", "ecs", "floci_owner_port", "alpha/4566", "floci.ecs-run", "current")));
        stubLeftoverListing(lifecycleManager, newKeys, legacyKeys);

        assertTrue(manager(lifecycleManager, namespacedConfig()).removeLeftoverContainers("current"));

        verify(lifecycleManager).removeIfExistsStrict("new-only", true);
        verify(lifecycleManager).removeIfExistsStrict("legacy-only", true);
        verify(lifecycleManager).removeIfExistsStrict("both", true);
        verify(lifecycleManager, never()).removeIfExistsStrict("current-run", true);
        verify(lifecycleManager, never()).removeIfExistsStrict("legacy-current-run", true);
    }

    @Test
    void removeLeftoverContainersLeavesAContainerWhoseNewAndLegacyLabelsDisagreeAlone() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        // Relabelled outside Floci: one owner key names a sibling deployment, or the run keys
        // disagree on whether the container is the current run's. Neither is removed.
        Map<String, Container> newKeys = Map.of(
                "owner-disagrees", leftover("owner-disagrees", Map.of(
                        "io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "old-run",
                        "floci_owner_port", "beta/4566", "floci.ecs-run", "old-run")),
                "run-disagrees", leftover("run-disagrees", Map.of(
                        "io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "current",
                        "floci_owner_port", "alpha/4566", "floci.ecs-run", "old-run")));
        Map<String, Container> legacyKeys = Map.of("run-disagrees", newKeys.get("run-disagrees"));
        stubLeftoverListing(lifecycleManager, newKeys, legacyKeys);

        assertTrue(manager(lifecycleManager, namespacedConfig()).removeLeftoverContainers("current"));

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString(), eq(true));
    }

    @Test
    void previousRunVolumesAreRemovedAfterContainersAndFailedRemovalIsRetried() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        stubLeftoverListing(lifecycle, Map.of(), Map.of());
        InspectVolumeResponse old = leftoverVolume("old", "alpha/4566", "previous");
        InspectVolumeResponse current = leftoverVolume("current", "alpha/4566", "current");
        InspectVolumeResponse foreign = leftoverVolume("foreign", "beta/4566", "previous");
        InspectVolumeResponse unowned = leftoverVolume("unowned", "alpha/4566", null);
        InspectVolumeResponse conflicting = leftoverVolume("conflicting", "alpha/4566", "previous");
        when(conflicting.getLabels()).thenReturn(Map.of("io.floci.service", "ecs",
                "io.floci.owner", "alpha/4566", "floci_owner_port", "beta/4566", "io.floci.ecs.run", "previous"));
        stubVolumeListing(lifecycle.getDockerClient(), List.of(old, current, foreign, unowned, conflicting));
        doThrow(new IllegalStateException("busy")).doNothing().when(lifecycle).removeVolumeStrict("old");
        EcsContainerManager manager = manager(lifecycle, namespacedConfig());

        assertTrue(manager.removeLeftoverContainers("current"));
        assertFalse(manager.removeLeftoverTaskVolumes("current"));
        assertTrue(manager.removeLeftoverTaskVolumes("current"));
        verify(lifecycle, times(2)).removeVolumeStrict("old");
        verify(lifecycle, never()).removeVolumeStrict("current");
        verify(lifecycle, never()).removeVolumeStrict("foreign");
        verify(lifecycle, never()).removeVolumeStrict("unowned");
        verify(lifecycle, never()).removeVolumeStrict("conflicting");
    }

    @Test
    void previousRunVolumeSweepWaitsForContainerRemoval() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        stubLeftoverListing(lifecycle, Map.of("old-container", leftover("old-container",
                Map.of("io.floci.service", "ecs", "io.floci.owner", "alpha/4566", "io.floci.ecs.run", "previous"))),
                Map.of());
        doThrow(new IllegalStateException("busy")).when(lifecycle).removeIfExistsStrict("old-container", true);
        assertFalse(manager(lifecycle, namespacedConfig()).removeLeftoverContainers("current"));
        verify(lifecycle.getDockerClient(), never()).listVolumesCmd();
    }

    @Test
    void previousRunVolumeSweepRetriesWhenInventoryCannotBeRead() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        stubLeftoverListing(lifecycle, Map.of(), Map.of());
        when(lifecycle.getDockerClient().listVolumesCmd()).thenThrow(new IllegalStateException("offline"));
        EcsContainerManager manager = manager(lifecycle, namespacedConfig());
        assertTrue(manager.removeLeftoverContainers("current"));
        assertFalse(manager.removeLeftoverTaskVolumes("current"));
        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void volumeRemovalWarnsOncePerVolumeAndResetsAfterSuccessfulRemoval() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        stubLeftoverListing(lifecycle, Map.of(), Map.of());
        stubVolumeListing(lifecycle.getDockerClient(), List.of(
                leftoverVolume("alpha", "alpha/4566", "previous"),
                leftoverVolume("beta", "alpha/4566", "previous")));
        doThrow(new IllegalStateException("busy")).when(lifecycle).removeVolumeStrict(anyString());
        EcsContainerManager manager = manager(lifecycle, namespacedConfig());
        List<LogRecord> records = LogCapture.capture(EcsContainerManager.class, () -> {
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
            doNothing().when(lifecycle).removeVolumeStrict("alpha");
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
            doThrow(new IllegalStateException("busy again")).when(lifecycle).removeVolumeStrict("alpha");
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
        });
        assertEquals(List.of(Level.WARNING.intValue(), Level.WARNING.intValue(),
                Level.FINE.intValue(), Level.FINE.intValue(), Level.FINE.intValue(),
                Level.WARNING.intValue(), Level.FINE.intValue()),
                records.stream().map(record -> record.getLevel().intValue()).toList());
        verify(lifecycle, times(4)).removeVolumeStrict("alpha");
        verify(lifecycle, times(4)).removeVolumeStrict("beta");
        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void volumeListingWarnsOnceAndResetsAfterSuccessfulListing() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        stubLeftoverListing(lifecycle, Map.of(), Map.of());
        ListVolumesCmd command = lifecycle.getDockerClient().listVolumesCmd();
        ListVolumesResponse empty = mock(ListVolumesResponse.class);
        when(empty.getVolumes()).thenReturn(List.of());
        when(command.exec()).thenThrow(new IllegalStateException("offline"))
                .thenThrow(new IllegalStateException("offline"))
                .thenReturn(empty, empty).thenThrow(new IllegalStateException("offline again"));
        EcsContainerManager manager = manager(lifecycle, namespacedConfig());
        List<LogRecord> records = LogCapture.capture(EcsContainerManager.class, () -> {
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
            assertTrue(manager.removeLeftoverTaskVolumes("current"));
            assertFalse(manager.removeLeftoverTaskVolumes("current"));
        });
        assertEquals(List.of(Level.WARNING.intValue(), Level.FINE.intValue(), Level.WARNING.intValue()),
                records.stream().map(record -> record.getLevel().intValue()).toList());
    }

    private static InspectVolumeResponse leftoverVolume(String name, String owner, String run) {
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(volume.getName()).thenReturn(name);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("io.floci.service", "ecs");
        labels.put("io.floci.owner", owner);
        if (run != null) {
            labels.put("io.floci.ecs.run", run);
        }
        when(volume.getLabels()).thenReturn(labels);
        return volume;
    }

    private static void stubVolumeListing(DockerClient docker, List<InspectVolumeResponse> volumes) {
        ListVolumesCmd command = mock(ListVolumesCmd.class, RETURNS_SELF);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        when(docker.listVolumesCmd()).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getVolumes()).thenReturn(volumes);
    }

    private static void stubLeftoverListing(ContainerLifecycleManager lifecycleManager,
                                            Map<String, Container> newKeys, Map<String, Container> legacyKeys) {
        DockerClient dockerClient = mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        stubVolumeListing(dockerClient, List.of());
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        ListContainersCmd byNewKeys = mock(ListContainersCmd.class);
        ListContainersCmd byLegacyKeys = mock(ListContainersCmd.class);
        when(listCmd.withLabelFilter(Map.of("io.floci.service", "ecs", "io.floci.owner", "alpha/4566")))
                .thenReturn(byNewKeys);
        when(listCmd.withLabelFilter(Map.of("io.floci.service", "ecs", "floci_owner_port", "alpha/4566")))
                .thenReturn(byLegacyKeys);
        when(byNewKeys.exec()).thenReturn(List.copyOf(newKeys.values()));
        when(byLegacyKeys.exec()).thenReturn(List.copyOf(legacyKeys.values()));
    }

    private static Container leftover(String id, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private static EmulatorConfig namespacedConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("alpha"));
        return config;
    }

    private static EcsContainerManager manager(ContainerLifecycleManager lifecycleManager) {
        return manager(lifecycleManager, mock(EmulatorConfig.class));
    }

    private static EcsContainerManager manager(ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
        return new EcsContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class),
                mock(LaunchedContainerAwsEnv.class), mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                mock(EcrRegistryManager.class), mock(HostVolumePolicy.class));
    }
}
