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
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AWS SDK baseline environment injected into ECS task containers by
 * {@link EcsContainerManager#startTask}. The baseline (region, credentials and the Floci
 * endpoint) is added as an <em>overridable default</em>: it is present unless the task
 * definition or a RunTask {@code containerOverride} sets the same key, in which case the
 * explicit value wins so a task's own configuration is never clobbered.
 *
 * <p>The container builder, lifecycle manager and {@link LaunchedContainerAwsEnv} are mocked,
 * so the test asserts the env that <em>would</em> be handed to Docker without launching a
 * container — runnable under {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerAwsBaselineTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private LaunchedContainerAwsEnv awsEnv;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);

        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any())).thenReturn(List.of(
                "AWS_DEFAULT_REGION=us-east-1",
                "AWS_REGION=us-east-1",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_ENDPOINT_URL=http://localhost:4566"));

        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), ecrRegistryManager);
    }

    @Test
    void injectsAwsBaselineAsOverridableDefaults() {
        ContainerDefinition app = containerDef("app", "app:latest",
                // Task def overrides one baseline key (AWS_REGION) and adds its own key.
                List.of(new KeyValuePair("AWS_REGION", "eu-west-1"),
                        new KeyValuePair("APP_ONLY", "1")));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, null, "us-east-1");

        // The baseline is resolved for the task's region.
        verify(awsEnv).sdkBaselineEnv(eq("us-east-1"), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(envCaptor.capture());
        List<String> env = envCaptor.getValue();

        // Baseline entries are injected...
        assertTrue(env.contains("AWS_ENDPOINT_URL=http://localhost:4566"),
                "AWS endpoint baseline should be injected into the task container");
        assertTrue(env.contains("AWS_ACCESS_KEY_ID=test"),
                "credential baseline should be injected when not overridden");
        // ...but the task def wins on a key conflict (baseline is overridable).
        assertTrue(env.contains("AWS_REGION=eu-west-1"),
                "task-def value should override the baseline on key conflict");
        assertFalse(env.contains("AWS_REGION=us-east-1"),
                "overridden baseline value should not remain");
        // Task-def-only entries are preserved.
        assertTrue(env.contains("APP_ONLY=1"));
    }

    @Test
    void runTaskOverrideWinsOverBaseline() {
        ContainerDefinition app = containerDef("app", "app:latest", List.of());

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));

        ContainerOverride override = new ContainerOverride();
        override.setName("app");
        override.setEnvironment(List.of(new KeyValuePair("AWS_ENDPOINT_URL", "http://override:9999")));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(override), "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(envCaptor.capture());
        List<String> env = envCaptor.getValue();

        assertTrue(env.contains("AWS_ENDPOINT_URL=http://override:9999"),
                "RunTask containerOverride should win over the baseline");
        assertFalse(env.contains("AWS_ENDPOINT_URL=http://localhost:4566"),
                "overridden baseline endpoint should not remain");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true"})
    void taskRoleUsesOnlyRelativeCredentialEndpointAndUniqueLinkLocalIp(boolean sharedNetworkOnly,
                                                                      boolean creationFails) {
        EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
        when(credentials.enabled()).thenReturn(true);
        EcsTaskRoleCredentials.IssuedCredentials issued = new EcsTaskRoleCredentials.IssuedCredentials(
                "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/role-task",
                "arn:aws:iam::000000000000:role/task-role", "/v2/credentials/ROLECREDENTIALTOKEN1234567890",
                new SessionCreds("ASIAROLE", "role-secret", "role-token"),
                Instant.parse("2030-01-01T00:00:00Z"), Instant.now());
        when(credentials.issue(anyString(), anyString(), anyString())).thenReturn(Optional.of(issued));
        when(credentials.linkLocalIp(anyString(), anyString())).thenReturn(Optional.of("169.254.170.3"));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        EcrRegistryManager ecr = mock(EcrRegistryManager.class);
        when(ecr.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));
        EmulatorConfig taskConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(taskConfig.services().ecs().dockerNetwork()).thenReturn(
                sharedNetworkOnly ? Optional.empty() : Optional.of("test-private"));
        when(taskConfig.services().dockerNetwork()).thenReturn(Optional.of("test-private"));
        manager = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                taskConfig, mock(RegionResolver.class), awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), ecr,
                credentials);
        ContainerDefinition app = containerDef("app", "app:latest", List.of(
                new KeyValuePair("AWS_ACCESS_KEY_ID", "attacker"),
                new KeyValuePair("AWS_PROFILE", "attacker-profile"),
                new KeyValuePair("AWS_CONTAINER_CREDENTIALS_FULL_URI", "http://attacker"),
                new KeyValuePair("AWS_CONTAINER_AUTHORIZATION_TOKEN", "attacker-token"),
                new KeyValuePair("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "/attacker-token"),
                new KeyValuePair("BOTO_CONFIG", "/attacker-config"),
                new KeyValuePair("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "/attacker"),
                new KeyValuePair("AWS_EC2_METADATA_DISABLED", "false")));
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("role-family");
        taskDef.setTaskRoleArn("arn:aws:iam::000000000000:role/task-role");
        taskDef.setContainerDefinitions(List.of(app));
        EcsTask task = new EcsTask();
        task.setTaskArn(issued.taskArn());

        if (creationFails) {
            when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("Docker start failed"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> manager.startTask(task, taskDef, null, "us-east-1"));
            verify(credentials).revokeTask(issued.taskArn());
            verify(credentials, org.mockito.Mockito.never()).releaseTaskNetwork(anyString());
            return;
        }
        manager.startTask(task, taskDef, null, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(envCaptor.capture());
        List<String> env = envCaptor.getValue();
        assertTrue(env.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + issued.relativeUri()));
        assertTrue(env.contains("AWS_EC2_METADATA_DISABLED=true"));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SESSION_TOKEN=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_PROFILE=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_CONTAINER_CREDENTIALS_FULL_URI=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_CONTAINER_AUTHORIZATION_TOKEN=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("BOTO_CONFIG=")));
        verify(builder).withLinkLocalIp("169.254.170.3");
    }

    @Test
    void credentialIssuanceFailureRevokesLeaseBeforeAnyContainerStarts() {
        EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
        when(credentials.enabled()).thenReturn(true);
        when(credentials.issue(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("issuance failed"));
        EmulatorConfig taskConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(taskConfig.services().ecs().dockerNetwork()).thenReturn(Optional.of("test-private"));
        assertCredentialStartRejected(credentials, taskConfig, IllegalStateException.class);
    }

    @Test
    void missingOrSharedDockerNetworkFailsClosedBeforeIssuance() {
        for (String network : List.of("", "host", "none", "bridge")) {
            EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
            when(credentials.enabled()).thenReturn(true);
            EmulatorConfig taskConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(taskConfig.services().ecs().dockerNetwork()).thenReturn(Optional.of(network));
            assertCredentialStartRejected(credentials, taskConfig,
                    io.github.hectorvent.floci.core.common.AwsException.class);
            verify(credentials, org.mockito.Mockito.never()).issue(anyString(), anyString(), anyString());
        }
    }

    private void assertCredentialStartRejected(EcsTaskRoleCredentials credentials,
                                              EmulatorConfig taskConfig,
                                              Class<? extends Throwable> failure) {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EcsContainerManager subject = new EcsContainerManager(containerBuilder, lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), taskConfig,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(EcrRegistryManager.class), credentials);
        TaskDefinition definition = new TaskDefinition();
        definition.setTaskRoleArn("arn:aws:iam::000000000000:role/task-role");
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/default/failure");
        org.junit.jupiter.api.Assertions.assertThrows(failure,
                () -> subject.startTask(task, definition, null, "us-east-1"));
        verify(credentials).revokeTask(task.getTaskArn());
        org.mockito.Mockito.verifyNoInteractions(lifecycle);
    }

    private static ContainerDefinition containerDef(String name, String image, List<KeyValuePair> env) {
        ContainerDefinition def = new ContainerDefinition();
        def.setName(name);
        def.setImage(image);
        def.setEnvironment(env);
        return def;
    }
}
