package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EcsContainerManagerPrivilegeTest {

    @Test
    void privilegedTaskIsRefusedBeforeDockerAccessByDefault() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        EcsContainerManager manager = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), mock(LaunchedContainerAwsEnv.class),
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                mock(EcrRegistryManager.class), new HostVolumePolicy(config));

        ContainerDefinition container = new ContainerDefinition();
        container.setName("worker");
        container.setPrivileged(true);
        TaskDefinition definition = new TaskDefinition();
        definition.setContainerDefinitions(List.of(container));

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.startTask(new EcsTask(), definition, List.of(), "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals("Privileged containers require floci.services.ecs.allow-privileged-containers"
                + " (FLOCI_SERVICES_ECS_ALLOW_PRIVILEGED_CONTAINERS) to be enabled.", failure.getMessage());
        verifyNoInteractions(containerBuilder, lifecycleManager);
    }
}
