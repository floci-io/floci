package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for container {@code portMappings} handling in
 * {@link EcsContainerManager#startTask} (regression coverage for issue #1270:
 * ECS task portMappings were ignored when launching Docker containers).
 *
 * <p>An explicit {@code hostPort} is published as a fixed host port binding so
 * the service is reachable at {@code localhost:<hostPort>}, in both native and
 * in-container modes. When {@code hostPort} is 0/unset, the container gets a
 * dynamic host port in native mode and is exposed-only when Floci runs inside
 * Docker.
 *
 * <p>In {@code awsvpc} network mode an explicit hostPort is never bound
 * literally (regression coverage for issue #1778: every AWS awsvpc task has its
 * own ENI, so identical host ports across tasks must not collide on the single
 * local Docker host) — such mappings behave as if hostPort were unset.
 *
 * <p>The container builder and lifecycle manager are mocked, so the test asserts
 * the port arguments that <em>would</em> be handed to Docker without launching
 * one — runnable under {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerPortMappingsTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));
        // resolveNetworkBindings() inspects the container after launch; deep stubs
        // make the empty-binding readback (Map.get(...) -> null) NPE-free.
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        SsmService ssmService = mock(SsmService.class);
        SecretsManagerService secretsManagerService = mock(SecretsManagerService.class);

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, ssmService, secretsManagerService);
    }

    private void startWith(List<PortMapping> portMappings) {
        startWith(portMappings, null);
    }

    private void startWith(List<PortMapping> portMappings, NetworkMode networkMode) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setPortMappings(portMappings);

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setNetworkMode(networkMode);

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(), "us-east-1");
    }

    @Test
    void explicitHostPortIsPublishedAsFixedBindingInNativeMode() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        startWith(List.of(new PortMapping(5000, 5000, "tcp")));

        // Regression guard for #1270: the requested hostPort must be honored,
        // not replaced by a dynamic (random) host port.
        verify(builder, times(1)).withPortBinding(5000, 5000);
        verify(builder, never()).withDynamicPort(5000);
        verify(builder, never()).withExposedPort(5000);
    }

    @Test
    void zeroHostPortUsesDynamicPortInNativeMode() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        startWith(List.of(new PortMapping(8080))); // hostPort defaults to 0

        verify(builder, times(1)).withDynamicPort(8080);
        verify(builder, never()).withPortBinding(eq(8080), anyInt());
    }

    @Test
    void awsvpcExplicitHostPortUsesDynamicPortInNativeMode() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);

        // Regression guard for #1778: awsvpc tasks have per-task ENIs on AWS,
        // so the literal hostPort must not be claimed on the shared Docker host.
        verify(builder, times(1)).withDynamicPort(80);
        verify(builder, never()).withPortBinding(eq(80), anyInt());
    }

    @Test
    void awsvpcExplicitHostPortIsExposedOnlyInContainerMode() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);

        verify(builder, times(1)).withExposedPort(80);
        verify(builder, never()).withPortBinding(eq(80), anyInt());
        verify(builder, never()).withDynamicPort(80);
    }

    @Test
    void twoAwsvpcTasksWithSameHostPortNeverBindLiterally() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);
        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);

        verify(builder, times(2)).withDynamicPort(80);
        verify(builder, never()).withPortBinding(eq(80), anyInt());
    }

    @Test
    void bridgeModeKeepsLiteralHostPortBinding() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        startWith(List.of(new PortMapping(5000, 5000, "tcp")), NetworkMode.bridge);

        verify(builder, times(1)).withPortBinding(5000, 5000);
        verify(builder, never()).withDynamicPort(5000);
    }

    @Test
    void inContainerModePublishesExplicitHostPortAndExposesDynamic() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        startWith(List.of(
                new PortMapping(5000, 5000, "tcp"), // explicit -> published in both modes
                new PortMapping(9090)));            // dynamic -> exposed only when in-container

        verify(builder, times(1)).withPortBinding(5000, 5000);
        verify(builder, times(1)).withExposedPort(9090);
        verify(builder, never()).withDynamicPort(9090);
        verify(builder, never()).withPortBinding(eq(9090), anyInt());
    }
}
