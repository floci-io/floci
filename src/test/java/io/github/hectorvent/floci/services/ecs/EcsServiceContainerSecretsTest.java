package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsServiceContainerSecretsTest {

    @Test
    void runTaskReturnsStoppedTaskWhenSecretResolutionFailsDuringDockerLaunch() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        // resolveSecretValue wraps any resolution failure as a ResourceInitializationError-coded
        // AwsException; EcsService keys off that code to pass the reason through verbatim.
        when(containerManager.startTask(any(), any(), any(), eq("us-east-1")))
                .thenThrow(new AwsException("ResourceInitializationError",
                        "ResourceInitializationError: unable to pull secrets or registry auth: /missing: not found",
                        400));

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsService service = new EcsService(new RegionResolver("us-east-1", "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class), null);
        service.createCluster("test-cluster", "us-east-1");

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setSecrets(List.of(new Secret("MISSING", "/missing")));
        service.registerTaskDefinition("secret-task", List.of(app), NetworkMode.bridge, null, null,
                null, null, null, "us-east-1");

        List<EcsTask> tasks = service.runTask("test-cluster", "secret-task", 1,
                LaunchType.EC2, null, null, List.of(), null, "us-east-1");

        assertEquals(1, tasks.size());
        EcsTask task = tasks.getFirst();
        assertEquals(TaskStatus.STOPPED.name(), task.getLastStatus());
        assertEquals(TaskStatus.STOPPED.name(), task.getDesiredStatus());
        // A ResourceInitializationError is AWS's exact stopped-reason wording, so it must be
        // passed through verbatim rather than wrapped in the generic "Failed to start:" prefix.
        assertTrue(task.getStoppedReason().startsWith("ResourceInitializationError"));
        assertTrue(task.getStoppedReason().contains("/missing"));
    }
}
