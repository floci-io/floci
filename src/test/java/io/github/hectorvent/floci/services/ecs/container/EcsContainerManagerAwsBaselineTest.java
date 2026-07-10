package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

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

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv);
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

    private static ContainerDefinition containerDef(String name, String image, List<KeyValuePair> env) {
        ContainerDefinition def = new ContainerDefinition();
        def.setName(name);
        def.setImage(image);
        def.setEnvironment(env);
        return def;
    }
}
