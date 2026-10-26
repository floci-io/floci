package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private EmulatorConfig config;
    private RegionResolver regionResolver;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));
        // resolveNetworkBindings() inspects the container after launch; deep stubs
        // make the empty-binding readback (Map.get(...) -> null) NPE-free.
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        containerDetector = mock(ContainerDetector.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        SsmService ssmService = mock(SsmService.class);
        SecretsManagerService secretsManagerService = mock(SecretsManagerService.class);
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, ssmService, secretsManagerService, mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class));
    }

    private EcsTask startWith(List<PortMapping> portMappings) {
        return startWith(portMappings, null);
    }

    private EcsTask startWith(List<PortMapping> portMappings, NetworkMode networkMode) {
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
        return task;
    }

    @Test
    void startTaskLabelsContainerWithResourceIdentity() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ecs",
                "io.floci.resource-id", "abc123",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
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
        verify(builder, times(1)).withPortBinding(80, 0, "127.0.0.1");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void awsvpcHostPublishingKeepsTheContainerPortByDefault(boolean runningInContainer) {
        when(containerDetector.isRunningInContainer()).thenReturn(runningInContainer);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);

        startWith(List.of(new PortMapping(80)), NetworkMode.awsvpc);

        verify(builder).withPortBinding(80, 80, "127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void awsvpcHostPublishingKeepsTheExplicitHostPortByDefault(boolean runningInContainer) {
        when(containerDetector.isRunningInContainer()).thenReturn(runningInContainer);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);

        startWith(List.of(new PortMapping(80, 18080, "tcp")), NetworkMode.awsvpc);

        verify(builder).withPortBinding(80, 18080, "127.0.0.1");
    }

    @Test
    void dynamicAllocationAloneDoesNotPublishAwsvpcPortsInDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);

        startWith(List.of(new PortMapping(80)), NetworkMode.awsvpc);

        verify(builder).withExposedPort(80);
        verify(builder, never()).withPortBinding(eq(80), anyInt(), anyString());
    }

    @Test
    void awsvpcPortUsesDynamicLoopbackBindingForHostRunners() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);

        startWith(List.of(new PortMapping(15_672)), NetworkMode.awsvpc);

        verify(builder, times(1)).withPortBinding(15_672, 0, "127.0.0.1");
        verify(builder, never()).withExposedPort(15_672);
        verify(builder, never()).withDynamicPort(15_672);
    }

    @Test
    void awsvpcHostPublishingDoesNotReserveTheEniPortOnTheSharedHost() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);

        startWith(List.of(new PortMapping(8080, 18_080, "tcp")), NetworkMode.awsvpc);

        verify(builder, times(1)).withPortBinding(8080, 0, "127.0.0.1");
    }

    @Test
    void protectedAwsvpcNamespaceUsesDynamicHostBindings() {
        ContainerDefinition app = new ContainerDefinition();
        app.setPortMappings(List.of(
                new PortMapping(6379),
                new PortMapping(8080, 18_080, "tcp")));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setContainerDefinitions(List.of(app));

        Map<Integer, Integer> bindings = EcsContainerManager.namespacePortBindings(
                taskDefinition, true, true, true);

        assertEquals(Map.of(6379, 0, 8080, 0), bindings);
    }

    @Test
    void protectedAwsvpcNamespaceKeepsDefaultContainerModePrivate() {
        ContainerDefinition app = new ContainerDefinition();
        app.setPortMappings(List.of(new PortMapping(6379)));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setContainerDefinitions(List.of(app));

        Map<Integer, Integer> bindings = EcsContainerManager.namespacePortBindings(
                taskDefinition, true, false, false);

        assertEquals(Map.of(), bindings);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedAwsvpcNamespaceKeepsStablePublishingByDefault(boolean runningInContainer) {
        ContainerDefinition app = new ContainerDefinition();
        app.setPortMappings(List.of(new PortMapping(6379), new PortMapping(8080, 18080, "tcp")));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setContainerDefinitions(List.of(app));

        Map<Integer, Integer> bindings = EcsContainerManager.namespacePortBindings(
                taskDefinition, runningInContainer, true, false);

        assertEquals(Map.of(6379, 6379, 8080, 18080), bindings);
    }

    @Test
    void protectedAwsvpcNamespaceUsesDynamicBindingsInNativeModeWithoutPublishingOptIn() {
        ContainerDefinition app = new ContainerDefinition();
        app.setPortMappings(List.of(new PortMapping(8080, 18080, "tcp")));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setContainerDefinitions(List.of(app));

        Map<Integer, Integer> bindings = EcsContainerManager.namespacePortBindings(
                taskDefinition, false, false, false);

        assertEquals(Map.of(8080, 0), bindings);
    }

    @Test
    void protectedAwsvpcNamespaceUsesDynamicBindingsInNativeModeWithOptIn() {
        ContainerDefinition app = new ContainerDefinition();
        app.setPortMappings(List.of(
                new PortMapping(6379),
                new PortMapping(8080, 18_080, "tcp")));
        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setContainerDefinitions(List.of(app));

        Map<Integer, Integer> bindings = EcsContainerManager.namespacePortBindings(
                taskDefinition, false, true, true);

        assertEquals(Map.of(6379, 0, 8080, 0), bindings);
    }

    @Test
    void twoAwsvpcTasksWithSameHostPortNeverBindLiterally() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);

        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);
        startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);

        verify(builder, times(2)).withPortBinding(80, 0, "127.0.0.1");
        verify(builder, never()).withPortBinding(eq(80), anyInt());
    }

    @Test
    void publishedPortsReportDockersAllocationEvenWhenFlociRunsInDocker() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);
        when(lifecycleManager.getDockerClient().inspectContainerCmd("docker-id").exec()
                .getNetworkSettings().getPorts().getBindings()).thenReturn(Map.of(
                        ExposedPort.tcp(80), new Ports.Binding[]{new Ports.Binding("127.0.0.1", "32781")}));

        EcsTask task = startWith(List.of(new PortMapping(80, 80, "tcp")), NetworkMode.awsvpc);

        assertEquals(32781, task.getContainers().getFirst().getNetworkBindings().getFirst().hostPort());
        assertEquals("127.0.0.1", task.getContainers().getFirst().getNetworkBindings().getFirst().bindIP());
    }

    @Test
    void awsvpcHostBindingUsesTheConfiguredInterface() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().ecs().publishAwsvpcPortsToHost()).thenReturn(true);
        when(config.services().ecs().dynamicAwsvpcHostPorts()).thenReturn(true);
        when(config.services().ecs().hostPortBindAddress()).thenReturn("192.0.2.10");

        startWith(List.of(new PortMapping(80)), NetworkMode.awsvpc);

        verify(builder).withPortBinding(80, 0, "192.0.2.10");
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

    @Test
    void firelensRouterDeclaringTheForwardPortIsRejected() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        ContainerDefinition router = new ContainerDefinition();
        router.setName("router");
        router.setImage("fluent/fluent-bit:latest");
        router.setPortMappings(List.of(new PortMapping(24224)));
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of(), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.startTask(task, taskDef, List.of(), "us-east-1"));

        assertEquals("FireLens port 24224 must not be exposed.", failure.getMessage());
        verify(builder, never()).withDynamicPort(24224);
    }
}
