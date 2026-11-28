package io.floci.aws.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.NetworkInterfaceListResult;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.FirelensConfiguration;
import io.floci.aws.services.ecs.model.LogConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.floci.aws.services.ecs.container.AwsvpcTaskFixtures.TASK_ARN;
import static io.floci.aws.services.ecs.container.AwsvpcTaskFixtures.awsvpcTask;
import static io.floci.aws.services.ecs.container.AwsvpcTaskFixtures.container;
import static io.floci.aws.services.ecs.container.AwsvpcTaskFixtures.taskDef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An awsvpc task's containers in one network namespace without security-group enforcement, held by
 * a helper the firewall manager starts with no firewall in it.
 */
class EcsContainerManagerSharedTaskNetworkTest {

    private static final String NETWORK = "floci-net";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/task-role";
    private static final String RELATIVE_URI = "/v2/credentials/abc";
    private static final String OTHER_TASK = "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/next";

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;
    private EmulatorConfig config;
    private Ec2Service ec2Service;
    private SecurityGroupFirewallManager firewallManager;
    private EcsTaskRoleCredentials taskRoleCredentials;
    private EcsTaskLinkLocalAddresses linkLocalAddresses;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(Optional.of(NETWORK));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        // Its confirmed removal goes through the strict removal each test stubs.
        when(lifecycleManager.stopAndConfirmRemoved(any(), any(), anyInt(), anyBoolean(), any())).thenCallRealMethod();
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().sharedTaskNetwork()).thenReturn(true);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ec2Service = mock(Ec2Service.class);
        firewallManager = mock(SecurityGroupFirewallManager.class);
        when(firewallManager.enabled()).thenReturn(false);
        when(firewallManager.createSharedNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "172.18.0.9"));
        taskRoleCredentials = mock(EcsTaskRoleCredentials.class);
        when(taskRoleCredentials.issue(any(), any(), any(), any())).thenReturn(Optional.of(RELATIVE_URI));
        linkLocalAddresses = new EcsTaskLinkLocalAddresses();

        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, regionResolver, awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class),
                ec2Service, firewallManager, taskRoleCredentials, mock(EcsCredentialsProxy.class),
                linkLocalAddresses);
    }

    @Test
    void sharedNamespacePublishesDnsBeforeWorkloadStartsAndRevokesOnCleanup() {
        Subnet subnet = new Subnet();
        subnet.setVpcId("vpc-dns");
        when(ec2Service.describeSubnets(eq("us-east-1"), eq(List.of("subnet-1")), any()))
                .thenReturn(List.of(subnet));
        ClientVpc expected = new ClientVpc("000000000000", "us-east-1", "vpc-dns");
        when(lifecycleManager.createAndStartEphemeral(any(), any())).thenAnswer(invocation -> {
            assertEquals(Optional.of(expected), manager.vpcForClient("172.18.0.9"));
            return new ContainerInfo("docker-id", Map.of());
        });
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(null, container("web", 80)),
                List.of(), "us-east-1");
        assertEquals(Optional.of(expected), manager.vpcForClient("172.18.0.9"));
        manager.cleanupStoppedTask(handle);
        assertTrue(manager.vpcForClient("172.18.0.9").isEmpty());
    }

    @Test
    void sharedDnsOwnershipSurvivesWorkloadStopsAndFailedHelperRemoval() {
        Subnet subnet = new Subnet();
        subnet.setVpcId("vpc-dns");
        when(ec2Service.describeSubnets(eq("us-east-1"), eq(List.of("subnet-1")), any()))
                .thenReturn(List.of(subnet));
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(null, container("web", 80)),
                List.of(), "us-east-1");
        ClientVpc expected = new ClientVpc("000000000000", "us-east-1", "vpc-dns");
        doThrow(new IllegalStateException("helper still running"))
                .when(firewallManager).removeSharedNamespaceStrict("abc123");
        assertThrows(IllegalStateException.class, () -> manager.stopTaskAndCollectExitCodes(handle));
        assertEquals(Optional.of(expected), manager.vpcForClient("172.18.0.9"));
        doNothing().when(firewallManager).removeSharedNamespaceStrict("abc123");
        manager.cleanupStoppedTask(handle);
        assertTrue(manager.vpcForClient("172.18.0.9").isEmpty());
    }

    @Test
    void everyContainerJoinsOneHelperThatHoldsTheTasksPorts() {
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(null,
                container("web", 8081), container("proxy", 80)), List.of(), "us-east-1");

        verify(builder, times(2)).withNetworkMode("container:helper-id");
        verify(builder, never()).withLabels(Map.of("io.floci.security-group.workload", "true"));
        verify(builder, never()).withPortBinding(anyInt(), anyInt());
        verify(builder, never()).withPortBinding(anyInt(), anyInt(), anyString());
        verify(builder, never()).withDynamicPort(anyInt());
        verify(builder, never()).withExposedPort(anyInt());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, Integer>> ports = ArgumentCaptor.forClass(Map.class);
        verify(firewallManager).createSharedNamespace(eq("ecs"), eq("abc123"), eq("000000000000"),
                eq("us-east-1"), any(), ports.capture(), any(), eq("127.0.0.1"), eq(List.of()));
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        expected.put(8081, 0);
        expected.put(80, 0);
        assertEquals(expected, ports.getValue());
        // Each container's network bindings are read off the helper, which publishes them.
        verify(dockerClient, atLeastOnce()).inspectContainerCmd("helper-id");
        verify(firewallManager, never()).createNamespace(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(firewallManager, never()).register(any(), any(), any());
        verify(ec2Service, never()).createNetworkInterface(any(), any(), any(), any(), any(), any(), any());
        assertEquals("eni-1", handle.getNetworkInterfaceId());
    }

    @Test
    void theHelperHoldsTheTasksOneCredentialAddress() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);

        manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 80), container("sidecar", 9000)),
                List.of(), "us-east-1");

        verify(firewallManager).createSharedNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(),
                any(), eq(List.of("169.254.170.1")));
        // A container joined to the helper has no network endpoint of its own to give an address to.
        verify(builder, never()).withLinkLocalIp(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder, times(2)).withEnv(env.capture());
        for (List<String> containerEnv : env.getAllValues()) {
            assertTrue(containerEnv.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + RELATIVE_URI),
                    containerEnv.toString());
        }
    }

    @Test
    void theHelperHoldsTheEniAddressOnTheVpcNetwork() {
        Subnet subnet = new Subnet();
        subnet.setVpcId("vpc-1");
        when(ec2Service.describeSubnets(eq("us-east-1"), eq(List.of("subnet-1")), any())).thenReturn(List.of(subnet));

        manager.startTask(awsvpcTask(), taskDef(null, container("web", 8081), container("proxy", 80)),
                List.of(), "us-east-1");

        verify(ec2Service).attachContainerToVpc("000000000000", "us-east-1", "vpc-1", "subnet-1", "helper-id",
                "10.0.0.10");
    }

    @Test
    void fireLensIsReachedOnLoopbackAsTheEcsAgentSetsItForAwsvpc() {
        when(lifecycleManager.create(any(), any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("router-id", Map.of()));
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-abc123/_data");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);
        ContainerDefinition router = container("log_router", 0);
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));
        ContainerDefinition app = container("app", 0);
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of("Name", "cloudwatch"), null));

        manager.startTask(awsvpcTask(), taskDef(null, app, router), List.of(), "us-east-1");

        verify(builder, times(2)).withNetworkMode("container:helper-id");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder, times(2)).withEnv(env.capture());
        assertTrue(env.getAllValues().stream().anyMatch(containerEnv -> containerEnv.contains("FLUENT_HOST=127.0.0.1")),
                env.getAllValues().toString());
    }

    @Test
    void stoppingRemovesTheHelperOnceTheContainersAreGoneAndThenFreesItsAddress() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 80)),
                List.of(), "us-east-1");

        manager.stopTaskAndCollectExitCodes(handle);

        verify(firewallManager).removeSharedNamespaceStrict("abc123");
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the stopped task's address is free again");
    }

    @Test
    void theHelperOutlivesAContainerDockerWouldNotRemove() {
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(null, container("app", 80)),
                List.of(), "us-east-1");
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("docker-id")).thenReturn(remove);
        doThrow(new IllegalStateException("removal in progress")).when(remove).exec();

        manager.stopTaskAndCollectExitCodes(handle);

        verify(firewallManager, never()).removeSharedNamespaceStrict(anyString());
    }

    @Test
    void aHelperDockerKeepsIsTakenOffTheVpcNetworkAndRetriedBeforeItsAddressIsFreed() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        when(firewallManager.sharedNamespaceName("abc123")).thenReturn("floci-aws-netns-abc123");
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        when(ec2Service.describeNetworkInterfaces(eq("us-east-1"), eq(List.of("eni-1")), anyMap(), eq(0), isNull()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        doThrow(new IllegalStateException("helper busy")).doNothing()
                .when(firewallManager).removeSharedNamespaceStrict("abc123");
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 80)),
                List.of(), "us-east-1");

        assertThrows(IllegalStateException.class, () -> manager.stopTaskAndCollectExitCodes(handle));

        assertTrue(handle.isNamespaceHelperPending(), "the handle keeps the helper's removal to retry");
        // So the ENI address it holds there is free when the task's ENI is released.
        verify(ec2Service).detachContainerFromVpc("000000000000", "us-east-1", "vpc-1", "floci-aws-netns-abc123");
        verify(taskRoleCredentials).revoke(TASK_ARN);
        assertEquals("169.254.170.3", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "169.254.170.1 is still the helper's, and .2 is the endpoint's own");
        linkLocalAddresses.releaseAll(OTHER_TASK);

        manager.stopTaskAndCollectExitCodes(handle);

        assertFalse(handle.isNamespaceHelperPending());
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the retried removal frees the helper's address");
    }

    @Test
    void aHelperThatCannotStartFailsTheLaunchAndFreesItsAddressOnceItIsGone() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        when(firewallManager.createSharedNamespace(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("cannot start the helper"));

        assertThrows(IllegalStateException.class, () -> manager.startTask(awsvpcTask(),
                taskDef(ROLE_ARN, container("app", 80)), List.of(), "us-east-1"));

        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
        // Confirmed by name, since a failed start may have left the helper behind.
        verify(firewallManager).removeSharedNamespaceStrict("abc123");
        verify(taskRoleCredentials).revoke(TASK_ARN);
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, OTHER_TASK));
    }

    @Test
    void aHelperAFailedStartLeftBehindKeepsItsAddressAndItsCleanup() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        when(firewallManager.createSharedNamespace(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("inspect failed after the helper started"));
        doThrow(new IllegalStateException("helper busy")).when(firewallManager).removeSharedNamespaceStrict("abc123");

        EcsTaskLaunchException failed = assertThrows(EcsTaskLaunchException.class, () -> manager.startTask(
                awsvpcTask(), taskDef(ROLE_ARN, container("app", 80)), List.of(), "us-east-1"));

        assertTrue(failed.getHandle().isNamespaceHelperPending(), "the service keeps the handle to retry");
        assertEquals("169.254.170.3", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the helper Docker kept still holds 169.254.170.1");
    }

    @Test
    void aFailedVpcAttachmentRemovesTheContainersAndTheHelper() {
        Subnet subnet = new Subnet();
        subnet.setVpcId("vpc-1");
        when(ec2Service.describeSubnets(eq("us-east-1"), eq(List.of("subnet-1")), any())).thenReturn(List.of(subnet));
        when(ec2Service.attachContainerToVpc(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("VPC network is gone"));

        assertThrows(IllegalStateException.class, () -> manager.startTask(awsvpcTask(),
                taskDef(null, container("web", 8081)), List.of(), "us-east-1"));

        verify(lifecycleManager).stopAndRemoveStrict(eq("docker-id"), any(), anyInt(), anyBoolean(), any());
        verify(firewallManager).removeSharedNamespaceStrict("abc123");
    }

    @Test
    void withTheSettingOffEachContainerKeepsANamespaceOfItsOwn() {
        when(config.services().ecs().sharedTaskNetwork()).thenReturn(false);

        manager.startTask(awsvpcTask(), taskDef(null, container("app", 80)), List.of(), "us-east-1");

        verify(firewallManager, never()).createSharedNamespace(any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        verify(builder, never()).withNetworkMode(anyString());
        verify(builder).withPortBinding(80, 0, "127.0.0.1");
    }

    @Test
    void enforcementGivesTheTaskItsProtectedNamespaceInstead() {
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("stop once the protected path is taken"));
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);

        assertThrows(IllegalStateException.class, () -> manager.startTask(awsvpcTask(),
                taskDef(null, container("app", 80)), List.of(), "us-east-1"));

        verify(firewallManager).createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any());
        verify(firewallManager, never()).createSharedNamespace(any(), any(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void aBridgeTaskKeepsANamespacePerContainer() {
        TaskDefinition bridge = taskDef(null, container("app", 80));
        bridge.setNetworkMode(NetworkMode.bridge);

        manager.startTask(awsvpcTask(), bridge, List.of(), "us-east-1");

        verify(firewallManager, never()).createSharedNamespace(any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        verify(builder, never()).withNetworkMode(anyString());
    }
}
