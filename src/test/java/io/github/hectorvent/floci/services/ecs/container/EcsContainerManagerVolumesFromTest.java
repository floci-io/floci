package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsContainerManagerVolumesFromTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder sourceBuilder;
    private ContainerBuilder.Builder appBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        containerBuilder = mock(ContainerBuilder.class);
        sourceBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        appBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer("sidecar:latest")).thenReturn(sourceBuilder);
        when(containerBuilder.newContainer("app:latest")).thenReturn(appBuilder);
        when(sourceBuilder.build()).thenReturn(mock(ContainerSpec.class));
        when(appBuilder.build()).thenReturn(mock(ContainerSpec.class));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("source-id", Map.of()))
                .thenReturn(new ContainerInfo("app-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), ecrRegistryManager, mock(HostVolumePolicy.class));
    }

    @Test
    void volumesFromStartsTheSourceFirstAndUsesItsDockerId() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setVolumesFrom(List.of(new VolumeFrom("source", true)));
        ContainerDefinition source = definition("source", "sidecar:latest");

        EcsTask ecsTask = task();
        manager.startTask(ecsTask, taskDefinition(List.of(app, source)), List.of(), "us-east-1");

        InOrder order = inOrder(containerBuilder);
        order.verify(containerBuilder).newContainer("sidecar:latest");
        order.verify(containerBuilder).newContainer("app:latest");
        verify(appBuilder).withVolumesFrom("source-id", true);
        assertEquals(
                List.of("app", "source"),
                ecsTask.getContainers().stream().map(Container::getName).toList());
    }

    @Test
    void volumesFromPreservesReadWriteMode() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setVolumesFrom(List.of(new VolumeFrom("source", false)));
        ContainerDefinition source = definition("source", "sidecar:latest");

        manager.startTask(task(), taskDefinition(List.of(source, app)), List.of(), "us-east-1");

        verify(appBuilder).withVolumesFrom("source-id", false);
    }

    @Test
    void unknownVolumesFromSourceFailsBeforeCreatingContainers() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setVolumesFrom(List.of(new VolumeFrom("missing", false)));

        assertThrows(IllegalArgumentException.class,
                () -> manager.startTask(task(), taskDefinition(List.of(app)), List.of(), "us-east-1"));

        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void cyclicVolumesFromFailsBeforeCreatingContainers() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setVolumesFrom(List.of(new VolumeFrom("source", false)));
        ContainerDefinition source = definition("source", "sidecar:latest");
        source.setVolumesFrom(List.of(new VolumeFrom("app", false)));

        assertThrows(IllegalArgumentException.class,
                () -> manager.startTask(task(), taskDefinition(List.of(app, source)), List.of(), "us-east-1"));

        verify(lifecycleManager, never()).createAndStart(any());
    }

    private static ContainerDefinition definition(String name, String image) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage(image);
        return definition;
    }

    private static TaskDefinition taskDefinition(List<ContainerDefinition> definitions) {
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("volumes-from-family");
        taskDefinition.setContainerDefinitions(definitions);
        return taskDefinition;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/volumesfrom1");
        return task;
    }
}
