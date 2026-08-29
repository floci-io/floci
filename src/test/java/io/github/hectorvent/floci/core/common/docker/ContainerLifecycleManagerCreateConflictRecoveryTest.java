package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A create retry can hit the daemon twice for the same logical create: the first attempt's
 * response is lost to a transient socket error ({@link DockerRetry}), but the daemon already
 * created the named container, so the retry's {@code createCmd.exec()} fails with a 409 name
 * conflict instead of a transient I/O error. Without recovery this surfaces as a create
 * failure while a live, untracked container sits on the daemon.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContainerLifecycleManager — create retry name-conflict recovery")
class ContainerLifecycleManagerCreateConflictRecoveryTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(java.util.Optional.empty());
    }

    @Test
    void createAdoptsContainerThatWonTheRaceOnRetryConflict() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("winning-container-id");
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
                List.of(), null, null, List.of());

        String containerId = manager().create(spec);

        assertEquals("winning-container-id", containerId);
    }

    @Test
    void createRethrowsConflictWhenNoNamedContainerExists() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }
}
