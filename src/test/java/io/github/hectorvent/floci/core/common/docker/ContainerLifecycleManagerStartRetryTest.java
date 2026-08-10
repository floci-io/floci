package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotModifiedException;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Starting a container must survive a transient docker I/O blip, exactly as creating one does.
 *
 * <p>{@link ContainerLifecycleManager#create} wraps its daemon call in {@link DockerRetry} because
 * the shared docker socket drops connections mid-call under load; {@code startCreated} called
 * {@code startContainerCmd().exec()} bare, so a single {@code Broken pipe} between create and start
 * failed the launch outright. That is not hypothetical: it destroyed an LZA Prepare stage — the
 * Lambda container launch failed with {@code java.io.IOException: Broken pipe}, which surfaced as a
 * {@code Lambda.InitError} on {@code Custom::LoadAcceleratorConfigTable}, rolled the stack back, and
 * (with termination protection on) left it unredeployable. Peak concurrency at the time was six
 * containers, so this is a plain socket blip, not pool exhaustion.
 *
 * <p>The retry must be idempotent-safe: docker answers {@code start} on an already-running container
 * with HTTP 304, which docker-java raises as {@link NotModifiedException}. When the daemon processed
 * a start whose response was lost to the broken pipe, the retry sees exactly that — and it means the
 * container is running, which is success, not failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerLifecycleManagerStartRetryTest {

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    private final ContainerSpec spec = new ContainerSpec("busybox:latest");

    /**
     * A client whose {@code startContainerCmd(...).exec()} defers to {@code behaviour}, which is
     * handed the 1-based attempt number and may throw. Attempts are counted in {@code execCalls}.
     */
    private DockerClient dockerWhoseStartExec(AtomicInteger execCalls, IntConsumer behaviour) {
        StartContainerCmd cmd = mock(StartContainerCmd.class);
        when(cmd.exec()).thenAnswer(inv -> {
            behaviour.accept(execCalls.incrementAndGet());
            return null;
        });
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.startContainerCmd("container-abc")).thenReturn(cmd);
        return docker;
    }

    private ContainerLifecycleManager manager(DockerClient docker) {
        return new ContainerLifecycleManager(
                docker, imageCacheService, containerDetector, portAllocator);
    }

    @Test
    void startCreatedRetriesTransientBrokenPipeThenSucceeds() {
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            if (attempt < 3) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
        });

        ContainerLifecycleManager.ContainerInfo info =
                manager(docker).startCreated("container-abc", spec);

        assertEquals("container-abc", info.containerId());
        assertEquals(3, execCalls.get(),
                "start must retry a transient Broken pipe, not fire the start exactly once");
    }

    @Test
    void startCreatedTreatsNotModifiedOnRetryAsSuccess() {
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            if (attempt == 1) {
                // The daemon started the container, then the socket died before the response.
                throw new RuntimeException(new IOException("Broken pipe"));
            }
            // The retry therefore finds it already running: HTTP 304.
            throw new NotModifiedException("Container already started");
        });

        ContainerLifecycleManager.ContainerInfo info = assertDoesNotThrow(
                () -> manager(docker).startCreated("container-abc", spec),
                "a 304 on retry means the container is running — that is success, not failure");

        assertEquals("container-abc", info.containerId());
        assertEquals(2, execCalls.get(), "the 304 must end the retry loop, not drive further attempts");
    }

    @Test
    void startCreatedDoesNotRetryNonTransientFailure() {
        AtomicInteger execCalls = new AtomicInteger();
        RuntimeException portConflict =
                new RuntimeException("Bind for 0.0.0.0:80 failed: port is already allocated");
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            throw portConflict;
        });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> manager(docker).startCreated("container-abc", spec));

        assertSame(portConflict, thrown, "a genuine daemon rejection must surface unchanged");
        assertEquals(1, execCalls.get(),
                "a port conflict never clears on a retry; retrying it just delays the failure");
    }

    @Test
    void adoptRetriesTransientBrokenPipeThenSucceeds() {
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            if (attempt < 3) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
        });

        // adopt() inspects first, sees a stopped container, and starts it.
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getState().getRunning()).thenReturn(Boolean.FALSE);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(docker.inspectContainerCmd("container-abc")).thenReturn(inspectCmd);

        manager(docker).adopt("container-abc", List.of());

        assertEquals(3, execCalls.get(),
                "adopting a stopped container must retry a transient Broken pipe on start");
    }
}
