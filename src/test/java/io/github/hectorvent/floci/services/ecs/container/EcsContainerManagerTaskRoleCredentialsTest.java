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
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manager's half of task-role credentials: issuing them for a task that asks for a role,
 * standing the endpoint up on the task's network, pointing every container at the same relative
 * URI, giving each its own address so it can reach that endpoint, and letting all of it go when
 * the task does.
 */
class EcsContainerManagerTaskRoleCredentialsTest {

    private static final String TASK_ARN = "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/ecsTaskRole";
    private static final String NETWORK = "floci-ecs-net";
    private static final String RELATIVE_URI = "/v2/credentials/0b5f1a6e-8c21-4c2e-9a4c-2f0d2b6c7a10";

    private ContainerBuilder.Builder builder;
    private ContainerBuilder containerBuilder;
    private EmulatorConfig config;
    private EcsTaskRoleCredentials taskRoleCredentials;
    private EcsCredentialsProxy credentialsProxy;
    private EcsTaskLinkLocalAddresses linkLocalAddresses;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(Optional.of(NETWORK));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);

        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(List.of(
                "AWS_REGION=us-east-1",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=baseline-token",
                "AWS_ENDPOINT_URL=http://localhost:4566"));
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        taskRoleCredentials = mock(EcsTaskRoleCredentials.class);
        when(taskRoleCredentials.issue(any(), any(), any(), any())).thenReturn(Optional.of(RELATIVE_URI));
        credentialsProxy = mock(EcsCredentialsProxy.class);
        linkLocalAddresses = new EcsTaskLinkLocalAddresses();

        manager = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecrRegistryManager,
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);
    }

    @Test
    void pointsEveryContainerOfATaskAtOneCredentialUriOnItsOwnAddress() {
        manager.startTask(task(), taskDef(ROLE_ARN, "app", "sidecar"), null, "us-east-1");

        verify(credentialsProxy).ensureProxyOn(NETWORK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder, times(2)).withEnv(env.capture());
        for (List<String> containerEnv : env.getAllValues()) {
            assertTrue(containerEnv.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + RELATIVE_URI),
                    "every container gets the task's relative URI: " + containerEnv);
        }

        // One address per container, and no two the same: they have separate routing tables, and
        // Docker would happily accept a duplicate.
        ArgumentCaptor<String> addresses = ArgumentCaptor.forClass(String.class);
        verify(builder, times(2)).withLinkLocalIp(addresses.capture());
        assertEquals(2, addresses.getAllValues().stream().distinct().count(),
                "each container needs its own address: " + addresses.getAllValues());
    }

    @Test
    void dropsTheBaselineCredentialsThatWouldShadowTheRole() {
        manager.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(env.capture());
        List<String> containerEnv = env.getValue();

        // The SDK reads the environment before the container-credentials endpoint, so leaving
        // these in place would silently use Floci's own credentials and never the task role.
        assertTrue(containerEnv.stream().noneMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")), containerEnv.toString());
        assertTrue(containerEnv.stream().noneMatch(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")), containerEnv.toString());
        assertTrue(containerEnv.stream().noneMatch(e -> e.startsWith("AWS_SESSION_TOKEN=")), containerEnv.toString());
        // Everything else the task needs to reach Floci stays.
        assertTrue(containerEnv.contains("AWS_ENDPOINT_URL=http://localhost:4566"), containerEnv.toString());
        assertTrue(containerEnv.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + RELATIVE_URI), containerEnv.toString());
    }

    @Test
    void keepsTheBaselineCredentialsForATaskWithNoRole() {
        manager.startTask(task(), taskDef(null, "app"), null, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(env.capture());
        assertTrue(env.getValue().contains("AWS_ACCESS_KEY_ID=test"), env.getValue().toString());
    }

    @Test
    void keepsCredentialsATaskSetsForItselfEvenWhenVendingARole() {
        TaskDefinition taskDef = taskDef(ROLE_ARN, "app");
        taskDef.getContainerDefinitions().getFirst().setEnvironment(
                List.of(new KeyValuePair("AWS_ACCESS_KEY_ID", "AKIAOWN")));

        manager.startTask(task(), taskDef, null, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(env.capture());
        // Only the baseline is dropped; a credential the task deliberately sets is its own call.
        assertTrue(env.getValue().contains("AWS_ACCESS_KEY_ID=AKIAOWN"), env.getValue().toString());
    }

    @Test
    void revokesCredentialsWhenTheProxyCannotBeStarted() {
        doThrow(new IllegalStateException("no route"))
                .when(credentialsProxy).ensureProxyOn(any());

        try {
            manager.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");
        } catch (RuntimeException expected) {
            // The proxy failure itself is not what this test is about.
        }

        // The session was registered before the proxy was asked for, and the task never starts,
        // so nothing else would ever revoke it.
        verify(taskRoleCredentials).revoke(TASK_ARN);
    }

    @Test
    void revokesCredentialsEvenWhenAContainerCouldNotBeRemoved() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        // Docker refuses the removal, so the task's containers never all come off.
        when(dockerClient.removeContainerCmd("docker-id")).thenThrow(new IllegalStateException("in use"));
        EcsContainerManager stopping = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), mock(LaunchedContainerAwsEnv.class), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), mock(EcrRegistryManager.class),
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);

        stopping.stopTaskAndCollectExitCodes(new EcsTaskHandle(TASK_ARN, Map.of("app", "docker-id"), Map.of()));

        // A stopping task must not keep usable credentials just because cleanup is unfinished:
        // the retry that would release them depends on a handle this path may not retain.
        verify(taskRoleCredentials).revoke(TASK_ARN);
    }

    @Test
    void revokesCredentialsWhenPreflightFailsBeforeAnyContainerExists() {
        EcrRegistryManager failingRegistry = mock(EcrRegistryManager.class);
        when(failingRegistry.rewriteImageUri(anyString())).thenThrow(new IllegalStateException("registry down"));
        EcsContainerManager failing = new EcsContainerManager(containerBuilder,
                mock(ContainerLifecycleManager.class), mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class),
                baselineAwsEnv(), mock(SsmService.class), mock(SecretsManagerService.class),
                mock(S3Service.class), failingRegistry, mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);

        try {
            failing.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");
        } catch (RuntimeException expected) {
            // The preflight failure itself is not what this test is about.
        }

        // Issued before the preflight ran, and the caller only marks the task STOPPED, so
        // nothing else would ever take these back.
        verify(taskRoleCredentials).revoke(TASK_ARN);
    }

    @Test
    void keepsAddressesReservedWhenAContainerCouldNotBeRemoved() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.removeContainerCmd("docker-id")).thenThrow(new IllegalStateException("in use"));
        EcsContainerManager stopping = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), baselineAwsEnv(), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), mock(EcrRegistryManager.class),
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);
        String held = linkLocalAddresses.allocate(NETWORK, TASK_ARN);

        stopping.stopTaskAndCollectExitCodes(new EcsTaskHandle(TASK_ARN, Map.of("app", "docker-id"), Map.of()));

        // That container may still be attached holding this address, so the next task must not
        // be handed the same one, even though the credentials are already revoked.
        assertNotEquals(held, linkLocalAddresses.allocate(NETWORK, "arn:aws:ecs:us-east-1:000000000000:task/c/next"));
        verify(taskRoleCredentials).revoke(TASK_ARN);
    }

    @Test
    void keepsAddressesReservedWhenCleanupCannotRemoveAContainer() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        // The reconciler's cleanup path is the other way a task's containers come off, and Docker
        // can refuse a removal here just as it can on the stop path.
        doThrow(new IllegalStateException("in use"))
                .when(lifecycleManager).stopAndRemoveStrict("docker-id", null);
        EcsContainerManager stopping = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), baselineAwsEnv(), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), rewritingRegistry(),
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);
        EcsTaskHandle handle = stopping.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");

        stopping.cleanupStoppedTask(handle);

        verify(taskRoleCredentials).revoke(TASK_ARN);
        // That container may still be attached holding the address it was given, so the next task
        // must not be handed the same one.
        assertNotEquals("169.254.170.1",
                linkLocalAddresses.allocate(NETWORK, "arn:aws:ecs:us-east-1:000000000000:task/c/next"));
    }

    @Test
    void keepsAddressesReservedWhenAFailedLaunchCannotRemoveAContainer() {
        ContainerLifecycleManager failing = mock(ContainerLifecycleManager.class);
        // The first container starts and the second does not, so the launch unwinds with one
        // container already up and holding an address.
        when(failing.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()))
                .thenThrow(new IllegalStateException("no daemon"));
        doThrow(new IllegalStateException("in use"))
                .when(failing).stopAndRemoveStrict("docker-id", null);
        EcsContainerManager failingManager = new EcsContainerManager(containerBuilder, failing,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), baselineAwsEnv(), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), rewritingRegistry(),
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);

        try {
            failingManager.startTask(task(), taskDef(ROLE_ARN, "app", "sidecar"), null, "us-east-1");
        } catch (RuntimeException expected) {
            // The launch failure itself is not what this test is about.
        }

        verify(taskRoleCredentials).revoke(TASK_ARN);
        assertNotEquals("169.254.170.1",
                linkLocalAddresses.allocate(NETWORK, "arn:aws:ecs:us-east-1:000000000000:task/c/next"));
    }

    @Test
    void issuesNothingForATaskWithNoRole() {
        manager.startTask(task(), taskDef(null, "app"), null, "us-east-1");

        verify(taskRoleCredentials, never()).issue(any(), any(), any(), any());
        verify(credentialsProxy, never()).ensureProxyOn(any());
        verify(builder, never()).withLinkLocalIp(any());
    }

    @Test
    void issuesNothingWhenTheFeatureIsDisabled() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(false);

        manager.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");

        verify(taskRoleCredentials, never()).issue(any(), any(), any(), any());
        verify(credentialsProxy, never()).ensureProxyOn(any());
    }

    @Test
    void startsNoProxyAndPublishesNoUriWhenTheRoleCannotBeResolved() {
        when(taskRoleCredentials.issue(any(), any(), any(), any())).thenReturn(Optional.empty());

        manager.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");

        // Issuing failed, so there is nothing to reach: no proxy, no address, no env var.
        verify(credentialsProxy, never()).ensureProxyOn(any());
        verify(builder, never()).withLinkLocalIp(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(env.capture());
        assertTrue(env.getValue().stream().noneMatch(e -> e.startsWith("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")),
                env.getValue().toString());
    }

    @Test
    void revokesCredentialsAndFreesAddressesWhenTheTaskStops() {
        EcsTaskHandle handle = manager.startTask(task(), taskDef(ROLE_ARN, "app", "sidecar"), null, "us-east-1");

        manager.cleanupStoppedTask(handle);

        verify(taskRoleCredentials).revoke(TASK_ARN);
        // The two addresses that task held are back in the pool, so the next task gets them.
        String reused = linkLocalAddresses.allocate(NETWORK, "arn:aws:ecs:us-east-1:000000000000:task/c/next");
        assertEquals("169.254.170.1", reused);
    }

    @Test
    void revokesCredentialsWhenTheLaunchFailsPartWayThrough() {
        ContainerLifecycleManager failing = mock(ContainerLifecycleManager.class);
        when(failing.createAndStart(any())).thenThrow(new IllegalStateException("no daemon"));
        EcsContainerManager failingManager = new EcsContainerManager(containerBuilder, failing,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), mock(LaunchedContainerAwsEnv.class), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), rewritingRegistry(),
                mock(HostVolumePolicy.class), null, null,
                taskRoleCredentials, credentialsProxy, linkLocalAddresses);

        try {
            failingManager.startTask(task(), taskDef(ROLE_ARN, "app"), null, "us-east-1");
        } catch (RuntimeException expected) {
            // The launch failure itself is not what this test is about.
        }

        verify(taskRoleCredentials).revoke(TASK_ARN);
        assertEquals("169.254.170.1", linkLocalAddresses.allocate(NETWORK, "arn:aws:ecs:us-east-1:000000000000:task/c/next"));
    }

    private LaunchedContainerAwsEnv baselineAwsEnv() {
        LaunchedContainerAwsEnv env = mock(LaunchedContainerAwsEnv.class);
        when(env.sdkBaselineEnv(anyString(), any())).thenReturn(List.of("AWS_ACCESS_KEY_ID=test"));
        return env;
    }

    private EcrRegistryManager rewritingRegistry() {
        EcrRegistryManager registry = mock(EcrRegistryManager.class);
        when(registry.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return registry;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN);
        return task;
    }

    private static TaskDefinition taskDef(String roleArn, String... containerNames) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setTaskRoleArn(roleArn);
        taskDef.setContainerDefinitions(Arrays.stream(containerNames).map(name -> {
            ContainerDefinition def = new ContainerDefinition();
            def.setName(name);
            def.setImage(name + ":latest");
            return def;
        }).toList());
        return taskDef;
    }
}
