package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotModifiedException;
import io.github.hectorvent.floci.config.EmulatorConfig;
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
 * Start semantics that must hold with retries living at the transport seam
 * ({@link RetryingDockerHttpClient}), not at this call site.
 *
 * <p>The load-bearing piece the transport cannot provide: when the daemon honoured a start whose
 * response was lost to a broken pipe, the transport's replay meets HTTP 304 — a perfectly
 * successful response at transport level, which docker-java converts to
 * {@link NotModifiedException} <em>above</em> the transport. The call site must treat it as
 * success, or a recovered blip becomes a hard launch failure.
 *
 * <p>Equally load-bearing in the other direction: this manager sees the docker API through a
 * client whose transport has already spent the full retry budget, so it must not loop again
 * itself — an outer loop would compound backoff on an already-exhausted inner one (~90s+ worst
 * case for a single call) and multiply daemon pressure exactly when the socket is saturated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerLifecycleManagerStartTest {

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private EmulatorConfig config;

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
                docker, imageCacheService, containerDetector, portAllocator, config);
    }

    @Test
    void startCreatedTreatsNotModifiedAsSuccess() {
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            // The transport's retry replayed a start the daemon had honoured before the socket
            // died; docker answered the replay with HTTP 304, raised here as NotModifiedException.
            throw new NotModifiedException("Container already started");
        });

        ContainerLifecycleManager.ContainerInfo info = assertDoesNotThrow(
                () -> manager(docker).startCreated("container-abc", spec),
                "a 304 means the container is running — that is success, not failure");

        assertEquals("container-abc", info.containerId());
        assertEquals(1, execCalls.get());
    }

    @Test
    void startCreatedDoesNotRetryOnTopOfTheTransport() {
        AtomicInteger execCalls = new AtomicInteger();
        RuntimeException transportGaveUp = new RuntimeException(new IOException("Broken pipe"));
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            throw transportGaveUp;
        });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> manager(docker).startCreated("container-abc", spec));

        assertSame(transportGaveUp, thrown,
                "a transient error surfacing here means the transport already spent the retry"
                        + " budget; it must surface unchanged");
        assertEquals(1, execCalls.get(),
                "no call-site loop: an outer retry would compound backoff on an exhausted"
                        + " inner one and multiply pressure on a saturated socket");
    }

    @Test
    void startCreatedDoesNotSwallowNonTransientFailure() {
        AtomicInteger execCalls = new AtomicInteger();
        RuntimeException portConflict =
                new RuntimeException("Bind for 0.0.0.0:80 failed: port is already allocated");
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            throw portConflict;
        });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> manager(docker).startCreated("container-abc", spec));

        assertSame(portConflict, thrown, "a genuine daemon rejection must surface unchanged");
        assertEquals(1, execCalls.get());
    }

    @Test
    void adoptTreatsNotModifiedAsSuccess() {
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseStartExec(execCalls, attempt -> {
            throw new NotModifiedException("Container already started");
        });

        // adopt() inspects first, sees a stopped container, and starts it.
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getState().getRunning()).thenReturn(Boolean.FALSE);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(docker.inspectContainerCmd("container-abc")).thenReturn(inspectCmd);

        assertDoesNotThrow(() -> manager(docker).adopt("container-abc", List.of()),
                "adopting a container that turns out to be running already is success");

        assertEquals(1, execCalls.get());
    }
}
