package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContainerLifecycleManager#createAndStart} must not leak the created
 * container when the start step fails (e.g. a host-port conflict, issue #1778):
 * retrying callers would otherwise accumulate {@code Created} containers, and
 * fixed-name callers would hit name conflicts on their next attempt.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerStartFailureTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private EmulatorConfig config;

    private ContainerLifecycleManager manager;
    private final ContainerSpec spec = new ContainerSpec("busybox:latest");

    @BeforeEach
    void setUp() {
        manager = spy(new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config));
    }

    @Test
    void createAndStartRemovesCreatedContainerWhenStartFails() {
        RuntimeException startFailure =
                new RuntimeException("Bind for 0.0.0.0:80 failed: port is already allocated");
        doReturn("container-id").when(manager).create(spec);
        doThrow(startFailure).when(manager).startCreated("container-id", spec);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> manager.createAndStart(spec));

        assertSame(startFailure, thrown, "original start failure must propagate");
        verify(removeCmd).exec();
    }

    @Test
    void createAndStartDoesNotRemoveContainerOnSuccess() {
        ContainerLifecycleManager.ContainerInfo info =
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of());
        doReturn("container-id").when(manager).create(spec);
        doReturn(info).when(manager).startCreated("container-id", spec);

        assertSame(info, manager.createAndStart(spec));

        verify(dockerClient, never()).removeContainerCmd(any(String.class));
    }

    @Test
    void startCreatedTranslatesKeyringQuotaError() {
        // github.com/floci-io/floci/issues/2243: crun/runc report the kernel session-keyring
        // quota (kernel.keys.maxkeys) as "Disk quota exceeded", which sends operators looking at
        // disk space instead of the actual cause under high concurrent container counts.
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("container-id")).thenReturn(startCmd);
        when(startCmd.exec()).thenThrow(new DockerException(
                "crun: join keyctl '12345': Disk quota exceeded: OCI runtime error", 500));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> manager.startCreated("container-id", spec));

        assertTrue(thrown.getMessage().contains("kernel.keys.maxkeys"),
                "translated message should name the actual kernel-keyring cause: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("sysctl"),
                "translated message should include the fix: " + thrown.getMessage());
    }

    @Test
    void startCreatedPropagatesUnrelatedDockerExceptionsUnchanged() {
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("container-id")).thenReturn(startCmd);
        DockerException portConflict = new DockerException(
                "Bind for 0.0.0.0:80 failed: port is already allocated", 500);
        when(startCmd.exec()).thenThrow(portConflict);

        DockerException thrown = assertThrows(DockerException.class,
                () -> manager.startCreated("container-id", spec));

        assertSame(portConflict, thrown, "unrelated Docker errors must propagate unchanged");
    }
}
