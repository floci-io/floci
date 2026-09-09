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
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ECR image URI rewrite applied by {@link EcsContainerManager#startTask}
 * (issue #2568): a real-AWS-shaped ECR URI in {@code containerDefinitions[].image} must be
 * rewritten to Floci's emulated registry before the image is handed to Docker, the same way
 * Lambda's {@code ContainerLauncher} already rewrites it via
 * {@link EcrRegistryManager#rewriteImageUri}. The reported task/container image (RunTask,
 * DescribeTasks) must keep the original, un-rewritten URI.
 *
 * <p>The container builder, lifecycle manager and {@link EcrRegistryManager} are mocked, so the
 * test asserts the image that <em>would</em> be handed to Docker without launching one —
 * runnable under {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerEcrRewriteTest {

    private static final String ECR_IMAGE = "123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1";
    private static final String REWRITTEN_IMAGE = "123456789012.dkr.ecr.us-east-1.localhost:5100/backend-user:1";

    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private EcrRegistryManager ecrRegistryManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
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
        SsmService ssmService = mock(SsmService.class);
        SecretsManagerService secretsManagerService = mock(SecretsManagerService.class);

        ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(ECR_IMAGE)).thenReturn(REWRITTEN_IMAGE);
        when(ecrRegistryManager.rewriteImageUri("sidecar:latest")).thenReturn("sidecar:latest");

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, ssmService, secretsManagerService,
                ecrRegistryManager);
    }

    @Test
    void ecrShapedImageIsRewrittenBeforeDockerPullButOriginalUriIsReported() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(ECR_IMAGE);
        ContainerDefinition sidecar = new ContainerDefinition();
        sidecar.setName("sidecar");
        sidecar.setImage("sidecar:latest");

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app, sidecar));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        // newContainer(...) is called once per container definition, in definition order.
        ArgumentCaptor<String> imageCaptor = ArgumentCaptor.forClass(String.class);
        verify(containerBuilder, org.mockito.Mockito.times(2)).newContainer(imageCaptor.capture());
        List<String> images = imageCaptor.getAllValues();
        assertEquals(REWRITTEN_IMAGE, images.get(0), "ECR-shaped image should be rewritten for the docker pull");
        assertEquals("sidecar:latest", images.get(1), "non-ECR image should pass through unchanged");

        // RunTask/DescribeTasks must keep reporting the original task-def image, not the rewrite.
        List<Container> containers = task.getContainers();
        assertEquals(ECR_IMAGE, containers.get(0).getImage(),
                "reported container image must stay the original ECR URI, not the rewritten one");
        assertEquals("sidecar:latest", containers.get(1).getImage());
    }

    @Test
    void registryStartupFailureOnALaterContainerLeavesNoContainerCreated() {
        ContainerDefinition sidecar = new ContainerDefinition();
        sidecar.setName("sidecar");
        sidecar.setImage("sidecar:latest");
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(ECR_IMAGE);

        when(ecrRegistryManager.rewriteImageUri(ECR_IMAGE))
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon"));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        // "sidecar" (plain image) comes first, "app" (ECR image, fails to rewrite) comes second.
        taskDef.setContainerDefinitions(List.of(sidecar, app));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        assertThrows(RuntimeException.class,
                () -> manager.startTask(task, taskDef, List.of(), "us-east-1"));

        // The earlier "sidecar" container must never have been created.
        verify(lifecycleManager, never()).createAndStart(any());
    }
}
