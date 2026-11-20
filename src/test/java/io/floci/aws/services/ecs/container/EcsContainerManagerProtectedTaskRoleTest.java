package io.floci.aws.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
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
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-role credentials for an awsvpc task under security-group enforcement: its containers share the
 * protected helper's namespace, so the helper holds the task's one link-local address, and that address
 * is handed on only once the helper is confirmed gone.
 */
class EcsContainerManagerProtectedTaskRoleTest {

    private static final String NETWORK = "floci-net";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/task-role";
    private static final String RELATIVE_URI = "/v2/credentials/abc";
    private static final String OTHER_TASK = "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/next";

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private SecurityGroupFirewallManager firewallManager;
    private EcsTaskRoleCredentials taskRoleCredentials;
    private EcsCredentialsProxy credentialsProxy;
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
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Ec2Service ec2Service = mock(Ec2Service.class);
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.describeNetworkInterfaces(eq("us-east-1"), eq(List.of("eni-1")), anyMap(), eq(0), isNull()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any())).thenReturn(List.of(group));

        firewallManager = mock(SecurityGroupFirewallManager.class);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "172.18.0.9"));
        taskRoleCredentials = mock(EcsTaskRoleCredentials.class);
        when(taskRoleCredentials.issue(any(), any(), any(), any())).thenReturn(Optional.of(RELATIVE_URI));
        credentialsProxy = mock(EcsCredentialsProxy.class);
        linkLocalAddresses = new EcsTaskLinkLocalAddresses();

        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, regionResolver, awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class),
                ec2Service, firewallManager, taskRoleCredentials, credentialsProxy, linkLocalAddresses);
    }

    @Test
    void theProtectedHelperHoldsTheTasksOneCredentialAddress() {
        manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 0), container("sidecar", 0)),
                List.of(), "us-east-1");

        verify(credentialsProxy).ensureProxyOn(NETWORK);
        verify(firewallManager).createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(),
                eq(List.of("169.254.170.1")));
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
    void aProtectedTaskWithNoRoleGivesItsHelperNoAddress() {
        manager.startTask(awsvpcTask(), taskDef(null, container("app", 0)), List.of(), "us-east-1");

        verify(firewallManager).createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(),
                eq(List.of()));
        verify(taskRoleCredentials, never()).issue(any(), any(), any(), any());
    }

    @Test
    void aLaunchThatFailsBeforeTheNamespaceIsNeededStartsNoHelper() {
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenThrow(new IllegalStateException("pull failed"));

        assertThrows(IllegalStateException.class, () -> manager.startTask(awsvpcTask(),
                taskDef(ROLE_ARN, container("app", 0)), List.of(), "us-east-1"));

        verify(firewallManager, never()).createNamespace(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(taskRoleCredentials).revoke(TASK_ARN);
    }

    @Test
    void stoppingRemovesTheHelperByNameBeforeItsAddressIsFreed() {
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 0)),
                List.of(), "us-east-1");

        manager.stopTaskAndCollectExitCodes(handle);

        verify(firewallManager).removeProtectedNamespaceStrict("abc123");
        verify(firewallManager).unregister("eni-1");
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the stopped task's address is free again");
    }

    @Test
    void aHelperDockerKeepsIsRetriedBeforeItsAddressIsFreed() {
        doThrow(new IllegalStateException("helper busy")).doNothing()
                .when(firewallManager).removeProtectedNamespaceStrict("abc123");
        EcsTaskHandle handle = manager.startTask(awsvpcTask(), taskDef(ROLE_ARN, container("app", 0)),
                List.of(), "us-east-1");

        assertThrows(IllegalStateException.class, () -> manager.stopTaskAndCollectExitCodes(handle));

        assertTrue(handle.isNamespaceHelperPending(), "the handle keeps the helper's removal to retry");
        verify(firewallManager, never()).unregister(anyString());
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
    void aNamespaceThatCannotBeBuiltFreesItsAddressOnceTheHelperIsGone() {
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("cannot prepare the protected namespace"));

        assertThrows(IllegalStateException.class, () -> manager.startTask(awsvpcTask(),
                taskDef(ROLE_ARN, container("app", 0)), List.of(), "us-east-1"));

        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
        // Confirmed by name, since a failed start may have left the helper behind.
        verify(firewallManager).removeProtectedNamespaceStrict("abc123");
        verify(taskRoleCredentials).revoke(TASK_ARN);
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, OTHER_TASK));
    }

    @Test
    void aHelperAFailedStartLeftBehindKeepsItsAddressAndItsCleanup() {
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("inspect failed after the helper started"));
        doThrow(new IllegalStateException("helper busy")).when(firewallManager).removeProtectedNamespaceStrict("abc123");

        EcsTaskLaunchException failed = assertThrows(EcsTaskLaunchException.class, () -> manager.startTask(
                awsvpcTask(), taskDef(ROLE_ARN, container("app", 0)), List.of(), "us-east-1"));

        assertTrue(failed.getHandle().isNamespaceHelperPending(), "the service keeps the handle to retry");
        assertEquals("169.254.170.3", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the helper Docker kept still holds 169.254.170.1");
    }

    @Test
    void aContainerThatCannotStartLeavesTheAddressWithAHelperDockerKeeps() {
        when(lifecycleManager.createAndStartEphemeral(any(), any()))
                .thenThrow(new IllegalStateException("container failed to start"));
        doThrow(new IllegalStateException("helper busy")).when(firewallManager).removeProtectedNamespaceStrict("abc123");

        EcsTaskLaunchException failed = assertThrows(EcsTaskLaunchException.class, () -> manager.startTask(
                awsvpcTask(), taskDef(ROLE_ARN, container("app", 0)), List.of(), "us-east-1"));

        assertTrue(failed.getHandle().isNamespaceHelperPending(), "the service keeps the handle to retry");
        assertEquals("169.254.170.3", linkLocalAddresses.allocate(NETWORK, OTHER_TASK),
                "the unregistered helper may still hold 169.254.170.1");
    }
}
