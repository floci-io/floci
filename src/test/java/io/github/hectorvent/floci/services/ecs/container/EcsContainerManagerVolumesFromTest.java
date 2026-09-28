package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.LogConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private ContainerLogStreamer logStreamer;
    private S3Service s3Service;
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

        logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        when(awsEnv.flociEndpoint()).thenReturn("http://host.docker.internal:4566");
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        s3Service = mock(S3Service.class);

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), s3Service, ecrRegistryManager, mock(HostVolumePolicy.class));
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
    void firelensRouterAndApplicationStartAfterTheirVolumeSource() {
        ContainerBuilder.Builder routerBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer("router:latest")).thenReturn(routerBuilder);
        when(routerBuilder.build()).thenReturn(mock(ContainerSpec.class));
        when(lifecycleManager.create(any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(eq("router-id"), any()))
                .thenReturn(new ContainerInfo("router-id", Map.of()));
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd("floci-aws-ecs-firelens-volumesfrom1")).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-volumesfrom1/_data");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);

        ContainerDefinition app = definition("app", "app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of("Name", "stdout"), null));
        app.setVolumesFrom(List.of(new VolumeFrom("source", true)));
        ContainerDefinition router = definition("router", "router:latest");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));
        router.setVolumesFrom(List.of(new VolumeFrom("source", false)));
        ContainerDefinition source = definition("source", "sidecar:latest");

        EcsTask ecsTask = task();
        EcsTaskHandle handle = manager.startTask(
                ecsTask, taskDefinition(List.of(app, router, source)), List.of(), "us-east-1");

        InOrder order = inOrder(containerBuilder);
        order.verify(containerBuilder).newContainer("sidecar:latest");
        order.verify(containerBuilder).newContainer("router:latest");
        order.verify(containerBuilder).newContainer("app:latest");
        InOrder lifecycleOrder = inOrder(lifecycleManager, copyCmd);
        lifecycleOrder.verify(lifecycleManager).createAndStart(any(ContainerSpec.class));
        lifecycleOrder.verify(lifecycleManager).create(any(ContainerSpec.class));
        lifecycleOrder.verify(copyCmd).exec();
        lifecycleOrder.verify(lifecycleManager).startCreated(eq("router-id"), any(ContainerSpec.class));
        lifecycleOrder.verify(lifecycleManager).createAndStart(any(ContainerSpec.class));
        verify(routerBuilder).withVolumesFrom("source-id", false);
        verify(appBuilder).withVolumesFrom("source-id", true);
        verify(lifecycleManager).ensureVolume("floci-aws-ecs-firelens-volumesfrom1");
        verify(routerBuilder).withNamedVolume("floci-aws-ecs-firelens-volumesfrom1", "/var/run");
        verify(copyCmd).withRemotePath("/fluent-bit/etc");
        verify(routerBuilder, never()).withLoopbackPortBinding(anyInt(), anyInt());
        verify(appBuilder, never()).withNetworkMode(anyString());

        ArgumentCaptor<LogConfig> logConfig = ArgumentCaptor.forClass(LogConfig.class);
        verify(appBuilder).withLogConfig(logConfig.capture());
        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getValue().getType());
        assertEquals("unix:///var/lib/docker/volumes/floci-ecs-firelens-volumesfrom1/_data/fluent.sock",
                logConfig.getValue().getConfig().get("fluentd-address"));
        assertEquals("app-firelens-volumesfrom1", logConfig.getValue().getConfig().get("tag"));
        verify(appBuilder, never()).withLogRotation();
        verify(sourceBuilder).withLogRotation();
        verify(routerBuilder).withLogRotation();
        verify(logStreamer, never()).attach(eq("app-id"), any(), any(), any(), any());
        verify(logStreamer).attach(eq("source-id"), any(), any(), eq("us-east-1"), any());
        verify(logStreamer).attach(eq("router-id"), any(), any(), eq("us-east-1"), any());
        assertEquals("floci-aws-ecs-firelens-volumesfrom1", handle.getFirelensVolumeName());
        assertEquals(List.of("source", "router", "app"), handle.getContainerIds().keySet().stream().toList());
        assertEquals(List.of("app", "router", "source"),
                ecsTask.getContainers().stream().map(Container::getName).toList());
    }

    @Test
    void firelensRouterDependingOnItsLoggingApplicationFailsBeforeCreatingContainers() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of(), null));
        ContainerDefinition router = definition("router", "router:latest");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));
        router.setVolumesFrom(List.of(new VolumeFrom("app", false)));
        ContainerDefinition source = definition("source", "sidecar:latest");

        assertThrows(IllegalArgumentException.class,
                () -> manager.startTask(task(), taskDefinition(List.of(source, router, app)),
                        List.of(), "us-east-1"));

        verify(containerBuilder, never()).newContainer(anyString());
        verify(lifecycleManager, never()).create(any());
        verify(lifecycleManager, never()).createAndStart(any());
        verify(lifecycleManager, never()).ensureVolume(anyString());
    }

    @Test
    void missingFirelensS3ConfigFailsBeforeCreatingItsVolumeSourceOrSocketVolume() {
        ContainerDefinition app = definition("app", "app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of("Name", "stdout"), null));
        app.setVolumesFrom(List.of(new VolumeFrom("source", true)));
        ContainerDefinition router = definition("router", "router:latest");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of(
                "config-file-type", "s3",
                "config-file-value", "arn:aws:s3:::firelens-configs/missing.conf")));
        router.setVolumesFrom(List.of(new VolumeFrom("source", false)));
        ContainerDefinition source = definition("source", "sidecar:latest");
        when(s3Service.getObject("firelens-configs", "missing.conf"))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.startTask(task(), taskDefinition(List.of(app, router, source)),
                        List.of(), "us-east-1"));

        assertEquals("ResourceInitializationError", failure.getErrorCode());
        assertEquals("Unable to download firelens s3 config file: unable to download s3 config "
                + "missing.conf from bucket firelens-configs: The specified key does not exist.",
                failure.getMessage());
        verify(s3Service).getObject("firelens-configs", "missing.conf");
        verify(containerBuilder, never()).newContainer(anyString());
        verify(lifecycleManager, never()).create(any());
        verify(lifecycleManager, never()).createAndStart(any());
        verify(lifecycleManager, never()).ensureVolume(anyString());
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
        taskDefinition.setRevision(1);
        taskDefinition.setContainerDefinitions(definitions);
        return taskDefinition;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/volumesfrom1");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");
        return task;
    }
}
