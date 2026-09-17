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
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Regression for the FireLens launch-loop rewrite dropping the awsvpc
 * security-group namespace join. prepareNetwork still creates the ENI;
 * this asserts the per-container loop actually joins it.
 */
class EcsContainerManagerSecurityGroupTest {

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;
    private ContainerDetector containerDetector;
    private RegionResolver regionResolver;
    private Ec2Service ec2Service;
    private SecurityGroupFirewallManager firewallManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ec2Service = mock(Ec2Service.class);
        firewallManager = mock(SecurityGroupFirewallManager.class);

        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                containerDetector, config, regionResolver, awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class),
                ec2Service, firewallManager);
    }

    @Test
    void awsvpcTaskWithFirewallJoinsHelperNamespaceAndSkipsHostPorts() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);

        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        EcsTaskHandle handle = manager.startTask(awsvpcTask(), awsvpcTaskDef(List.of(new PortMapping(80, 80, "tcp"))),
                List.of(), "us-east-1");

        verify(builder).withNetworkMode("container:helper-id");
        verify(builder).withLabels(Map.of("floci.security-group-workload", "true"));
        verify(builder, never()).withPortBinding(anyInt(), anyInt());
        verify(builder, never()).withDynamicPort(anyInt());
        verify(builder, never()).withExposedPort(anyInt());
        verify(dockerClient).inspectContainerCmd("helper-id");
        verify(dockerClient, never()).inspectContainerCmd("docker-id");
        verify(firewallManager).register(any(), eq("helper-id"), any());
        assertEquals("eni-1", handle.getNetworkInterfaceId());
        assertEquals("us-east-1", handle.getRegion());
    }

    @Test
    void disabledFirewallDoesNotJoinNamespace() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(false);

        manager.startTask(awsvpcTask(), awsvpcTaskDef(List.of(new PortMapping(80, 80, "tcp"))),
                List.of(), "us-east-1");

        verify(builder, never()).withNetworkMode(anyString());
        verify(builder, never()).withLabels(Map.of("floci.security-group-workload", "true"));
        verify(ec2Service, never()).createNetworkInterface(any(), any(), any(), any(), any(), any(), any());
        verify(builder).withDynamicPort(80);
    }

    @Test
    void stopTaskUnregistersEni() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        DockerClient stopClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient, dockerClient, stopClient);

        EcsTaskHandle handle = manager.startTask(awsvpcTask(), awsvpcTaskDef(List.of()),
                List.of(), "us-east-1");
        manager.stopTaskAndCollectExitCodes(handle);

        verify(firewallManager).unregister("eni-1");
        verify(ec2Service).deleteNetworkInterface("us-east-1", "eni-1");
    }

    @Test
    void firelensRouterAndAppBothJoinTheHelperNamespace() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        when(lifecycleManager.create(any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(anyString(), any()))
                .thenReturn(new ContainerInfo("router-id", Map.of()));
        var inspectVolumeCmd = mock(com.github.dockerjava.api.command.InspectVolumeCmd.class);
        var volume = mock(com.github.dockerjava.api.command.InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-abc123/_data");
        var copyCmd = mock(com.github.dockerjava.api.command.CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);

        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration(
                "fluentbit", Map.of()));
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new io.github.hectorvent.floci.services.ecs.model.LogConfiguration(
                "awsfirelens", Map.of("Name", "cloudwatch"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-family");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app, router));

        manager.startTask(awsvpcTask(), taskDef, List.of(), "us-east-1");

        verify(builder, times(2)).withNetworkMode("container:helper-id");
        verify(builder, times(2)).withLabels(Map.of("floci.security-group-workload", "true"));
    }

    private static EcsTask awsvpcTask() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-1"));
        awsvpc.setSecurityGroups(List.of("sg-1"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(awsvpc);
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setNetworkConfiguration(network);
        return task;
    }

    private static TaskDefinition awsvpcTaskDef(List<PortMapping> ports) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setPortMappings(ports);
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }
}
