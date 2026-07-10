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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RunTask {@code containerOverrides} handling in
 * {@link EcsContainerManager#startTask}: a per-container override matched by name
 * must replace the task-def command and merge over the task-def environment
 * (override wins on key conflict); a container with no matching override is
 * launched unchanged.
 *
 * <p>The container builder and lifecycle manager are mocked, so the test asserts
 * the command/env that <em>would</em> be handed to Docker without launching one —
 * runnable under {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerOverridesTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        // Fluent builder: every withX() returns the builder; build() returns null
        // (we assert on the captured withCmd/withEnv arguments, not on the spec).
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv);
    }

    @Test
    void overrideReplacesCommandAndMergesEnvironmentMatchedByName() {
        // Two container defs in the task; only "app" has a matching override.
        ContainerDefinition app = containerDef("app", "app:latest",
                List.of("app-default"),
                List.of(new KeyValuePair("SHARED", "base"), new KeyValuePair("BASE_ONLY", "b")));
        ContainerDefinition sidecar = containerDef("sidecar", "sidecar:latest",
                List.of("sidecar-default"),
                List.of(new KeyValuePair("SIDE_ONLY", "s")));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app, sidecar));

        ContainerOverride appOverride = new ContainerOverride();
        appOverride.setName("app");
        appOverride.setCommand(List.of("run", "--flag"));
        appOverride.setEnvironment(List.of(
                new KeyValuePair("SHARED", "override"),   // wins over task-def SHARED=base
                new KeyValuePair("NEW_ONLY", "n")));       // added

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(appOverride), "us-east-1");

        // newContainer/withCmd/withEnv are called once per container definition,
        // in definition order: index 0 = app (overridden), index 1 = sidecar (untouched).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(builder, org.mockito.Mockito.times(2)).withCmd(cmdCaptor.capture());
        org.mockito.Mockito.verify(builder, org.mockito.Mockito.times(2)).withEnv(envCaptor.capture());

        List<List<String>> commands = cmdCaptor.getAllValues();
        List<List<String>> envs = envCaptor.getAllValues();

        // app: override command replaces the task-def command.
        assertEquals(List.of("run", "--flag"), commands.get(0),
                "override command should replace the task-def command for the matched container");
        // app: environment merged, override wins on conflict, new key added, untouched key kept.
        List<String> appEnv = envs.get(0);
        assertTrue(appEnv.contains("SHARED=override"), "override env should win on key conflict");
        assertFalse(appEnv.contains("SHARED=base"), "task-def value should be overridden");
        assertTrue(appEnv.contains("BASE_ONLY=b"), "non-overridden task-def env should be kept");
        assertTrue(appEnv.contains("NEW_ONLY=n"), "override-only env should be added");

        // sidecar: no matching override -> launched with task-def command and env unchanged.
        assertEquals(List.of("sidecar-default"), commands.get(1),
                "unmatched container should keep its task-def command");
        assertEquals(List.of("SIDE_ONLY=s"), envs.get(1),
                "unmatched container should keep its task-def environment");
    }

    private static ContainerDefinition containerDef(String name, String image,
                                                    List<String> command, List<KeyValuePair> env) {
        ContainerDefinition def = new ContainerDefinition();
        def.setName(name);
        def.setImage(image);
        def.setCommand(command);
        def.setEnvironment(env);
        return def;
    }
}
