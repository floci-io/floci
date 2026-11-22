package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerServiceBudgetTest {

    private static final String SERVICE = "budget-test-rds";

    @AfterEach
    void forgetContainers() {
        for (int i = 0; i < 4; i++) {
            DockerContainerServices.forget("budget-container-" + i);
        }
    }

    // Catches: the AWS error a client sees for an exhausted budget, which decides whether its SDK backs off.
    @Test
    void anExhaustedBudgetIsTheServicesOwnCapacityError() {
        DockerCapacityExceededException lambda = new DockerCapacityExceededException("lambda", 2);
        assertEquals("TooManyRequestsException", lambda.getErrorCode());
        assertEquals(429, lambda.getHttpStatus());

        DockerCapacityExceededException rds = new DockerCapacityExceededException("rds", 2);
        assertEquals("InsufficientDBInstanceCapacity", rds.getErrorCode());
        assertEquals(400, rds.getHttpStatus());
        assertTrue(rds.getMessage().contains("floci.docker.service-max-containers.rds"), rds.getMessage());

        assertEquals("ThrottlingException", new DockerCapacityExceededException("ecs", 1).getErrorCode());
    }

    // Catches: a service's removed containers still counting against it, or a reservation never coming back.
    @Test
    void liveContainersAndReservationsCountUntilRemovedOrReleased() {
        DockerContainerServices.record("budget-container-0", "budget-name-0",
                Map.of(ContainerStorageHelper.SERVICE_LABEL, SERVICE));
        DockerServiceBudget.reserve(SERVICE, 2);

        assertThrows(DockerCapacityExceededException.class, () -> DockerServiceBudget.reserve(SERVICE, 2),
                "one live container and one reservation fill a budget of two");

        DockerServiceBudget.release(SERVICE);
        assertDoesNotThrow(() -> DockerServiceBudget.reserve(SERVICE, 2));
        DockerServiceBudget.release(SERVICE);

        DockerContainerServices.forget("budget-name-0");
        assertEquals(0, DockerContainerServices.liveContainers(SERVICE), "a remove by name forgets the container");
    }

    // Catches: the budget not enforced at the one place every container create passes, or a create over
    // budget still reaching the daemon.
    @Test
    void aCreatePastTheBudgetFailsBeforeReachingTheDaemon() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(docker.createContainerCmd(any())).thenReturn(create);
        AtomicInteger ids = new AtomicInteger();
        when(create.exec()).thenAnswer(invocation -> {
            CreateContainerResponse response = mock(CreateContainerResponse.class);
            when(response.getId()).thenReturn("budget-container-" + ids.getAndIncrement());
            return response;
        });
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        when(dockerConfig.serviceMaxContainers()).thenReturn(Map.of(SERVICE, 1));
        ImageCacheService images = mock(ImageCacheService.class);
        when(images.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, docker, images, null, config);
        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, Map.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(ContainerStorageHelper.SERVICE_LABEL, SERVICE), null, false,
                null, List.of(), null, null, List.of());

        assertEquals("budget-container-0", manager.create(spec));
        DockerCapacityExceededException e = assertThrows(DockerCapacityExceededException.class,
                () -> manager.create(spec));
        assertEquals(SERVICE, e.service());
        verify(create, times(1)).exec();

        DockerContainerServices.forget("budget-container-0");
        assertEquals("budget-container-1", manager.create(spec), "a removed container frees its place");
    }
}
