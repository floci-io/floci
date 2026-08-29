package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsContainerManagerLifecycleTest {

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

    private static EcsContainerManager manager(ContainerLifecycleManager lifecycleManager) {
        return new EcsContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class),
                mock(LaunchedContainerAwsEnv.class), mock(SsmService.class), mock(SecretsManagerService.class),
                mock(EcrRegistryManager.class));
    }
}
