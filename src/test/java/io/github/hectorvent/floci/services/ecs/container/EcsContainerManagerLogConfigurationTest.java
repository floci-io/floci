package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A container's output reaches CloudWatch Logs only through the {@code awslogs} driver, in the
 * group and stream its log configuration names. Other non-FireLens containers stay on the console.
 * Docker is mocked, so no daemon is needed.
 */
class EcsContainerManagerLogConfigurationTest {

    private static final String TASK_ARN = "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123";

    private ContainerLogStreamer logStreamer;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        logStreamer = mock(ContainerLogStreamer.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(RegionResolver.class), awsEnv, mock(SsmService.class), mock(SecretsManagerService.class),
                mock(S3Service.class), ecrRegistryManager, mock(HostVolumePolicy.class));
    }

    @Test
    void awslogsSendsToTheConfiguredGroupAndPrefixedStream() {
        startTask(new LogConfiguration("awslogs", Map.of(
                "awslogs-group", "my-app",
                "awslogs-region", "us-east-1",
                "awslogs-stream-prefix", "web"), null));

        verify(logStreamer).attachForAccount(eq("000000000000"), eq("docker-id"), eq("my-app"), eq("web/main/abc123"),
                eq("us-east-1"), anyString());
    }

    @Test
    void awslogsWithoutStreamPrefixNamesTheStreamAfterTheDockerContainer() {
        startTask(new LogConfiguration("awslogs", Map.of("awslogs-group", "my-app"), null));

        verify(logStreamer).attachForAccount(eq("000000000000"), eq("docker-id"), eq("my-app"), eq("docker-id"),
                eq("us-east-1"), anyString());
    }

    @Test
    void awslogsRegionOptionChoosesTheLogsRegion() {
        startTask(new LogConfiguration("awslogs", Map.of(
                "awslogs-group", "my-app",
                "awslogs-region", "eu-west-1",
                "awslogs-stream-prefix", "web"), null));

        verify(logStreamer).attachForAccount(eq("000000000000"), eq("docker-id"), eq("my-app"), eq("web/main/abc123"),
                eq("eu-west-1"), anyString());
    }

    @Test
    void awslogsSendsToTheGroupInTheAccountOfTheTask() {
        // Docker's log threads have no request context, so the account comes from the task.
        startTask("arn:aws:ecs:us-east-1:111111111111:task/test-cluster/abc123",
                new LogConfiguration("awslogs", Map.of("awslogs-group", "my-app"), null));

        verify(logStreamer).attachForAccount(eq("111111111111"), eq("docker-id"), eq("my-app"), eq("docker-id"),
                eq("us-east-1"), anyString());
    }

    @Test
    void containerWithoutLogConfigurationOnlyStreamsToTheConsole() {
        startTask(null);

        verify(logStreamer).attachConsoleOnly("docker-id", "ecs:logs-family:main");
        verify(logStreamer, never()).attachForAccount(any(), any(), any(), any(), any(), any());
    }

    @Test
    void otherLogDriverOnlyStreamsToTheConsole() {
        startTask(new LogConfiguration("splunk", Map.of("splunk-url", "https://splunk.example.com"), null));

        verify(logStreamer).attachConsoleOnly("docker-id", "ecs:logs-family:main");
        verify(logStreamer, never()).attachForAccount(any(), any(), any(), any(), any(), any());
    }

    @Test
    void awslogsWithoutGroupOnlyStreamsToTheConsole() {
        startTask(new LogConfiguration("awslogs", Map.of("awslogs-stream-prefix", "web"), null));

        verify(logStreamer).attachConsoleOnly("docker-id", "ecs:logs-family:main");
        verify(logStreamer, never()).attachForAccount(any(), any(), any(), any(), any(), any());
    }

    private void startTask(LogConfiguration logConfiguration) {
        startTask(TASK_ARN, logConfiguration);
    }

    private void startTask(String taskArn, LogConfiguration logConfiguration) {
        ContainerDefinition main = new ContainerDefinition();
        main.setName("main");
        main.setImage("busybox:latest");
        main.setLogConfiguration(logConfiguration);

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("logs-family");
        taskDef.setContainerDefinitions(List.of(main));

        EcsTask task = new EcsTask();
        task.setTaskArn(taskArn);

        manager.startTask(task, taskDef, List.of(), "us-east-1");
    }
}
