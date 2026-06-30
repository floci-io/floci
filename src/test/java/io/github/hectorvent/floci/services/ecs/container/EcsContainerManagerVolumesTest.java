package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for task-definition {@code volumes} + container {@code mountPoints} handling in
 * {@link EcsContainerManager#startTask}: a container mountPoint resolves its source volume to
 * the task-level volume's host source path and bind-mounts it at the container path —
 * read-only via {@code withReadOnlyBind}, read-write via {@code withBind}. A mountPoint whose
 * source volume is not declared in the task definition is skipped (no bind).
 *
 * <p>The container builder and lifecycle manager are mocked, so the test asserts the bind
 * arguments that <em>would</em> be handed to Docker without launching one — runnable under
 * {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerVolumesTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver);
    }

    @Test
    void mountPointsResolveTaskVolumesToHostBindsByAccessMode() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(
                new MountPoint("config-vol", "/app/config.yml", true),    // read-only
                new MountPoint("aws-vol", "/root/.aws", false),           // read-write
                new MountPoint("missing-vol", "/app/nope", true)));       // unresolved -> skipped

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(
                new Volume("config-vol", "/host/abs/app/config.yml"),
                new Volume("aws-vol", "/host/abs/home/.aws")));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        // Read-only mountPoint -> withReadOnlyBind(hostSourcePath, containerPath).
        ArgumentCaptor<String> roHost = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> roPath = ArgumentCaptor.forClass(String.class);
        verify(builder, times(1)).withReadOnlyBind(roHost.capture(), roPath.capture());
        assertEquals("/host/abs/app/config.yml", roHost.getValue());
        assertEquals("/app/config.yml", roPath.getValue());

        // Read-write mountPoint -> withBind(hostSourcePath, containerPath).
        ArgumentCaptor<String> rwHost = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rwPath = ArgumentCaptor.forClass(String.class);
        verify(builder, times(1)).withBind(rwHost.capture(), rwPath.capture());
        assertEquals("/host/abs/home/.aws", rwHost.getValue());
        assertEquals("/root/.aws", rwPath.getValue());

        // The unresolved "missing-vol" mountPoint contributes no bind beyond the two above.
        verify(builder, never()).withBind("/app/nope", "/app/nope");
    }

    @Test
    void efsVolumeMountPointsResolveToSharedNamedVolumesByAccessMode() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(
                new MountPoint("customer-data", "/mnt/efs", false),    // read-write
                new MountPoint("shared-ro", "/mnt/shared", true)));    // read-only

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("efs-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(
                new Volume("customer-data", null,
                        new EfsVolumeConfiguration("fs-0123456789abcdef0", "/dps", "ENABLED", null, null, null)),
                new Volume("shared-ro", null,
                        new EfsVolumeConfiguration("fs-00000000000000001", null, null, null, null, null))));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/efs1");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        // Each EFS volume -> a shared local Docker named volume keyed by file system id,
        // read-write or read-only per the mountPoint.
        verify(builder, times(1)).withNamedVolume("floci-efs-fs-0123456789abcdef0", "/mnt/efs", false);
        verify(builder, times(1)).withNamedVolume("floci-efs-fs-00000000000000001", "/mnt/shared", true);

        // EFS volumes are never bind-mounted as host paths.
        verify(builder, never()).withBind(any(), any());
        verify(builder, never()).withReadOnlyBind(any(), any());
    }
}
