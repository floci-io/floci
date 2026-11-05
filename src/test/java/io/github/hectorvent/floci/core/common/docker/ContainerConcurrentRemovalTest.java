package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitContainerCondition;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerConcurrentRemovalTest {
    private final DockerClient docker = mock(DockerClient.class);
    private final InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    private final WaitContainerCmd wait = mock(WaitContainerCmd.class, RETURNS_SELF);
    private final Closeable logs = mock(Closeable.class);
    private final Closeable waitStream = mock(Closeable.class);
    private final ConflictException conflict = new ConflictException("removal already in progress");
    private final ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, mock(ImageCacheService.class), mock(ContainerDetector.class), mock(EmulatorConfig.class));

    private void removalConflict(boolean running) {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        InspectContainerResponse response = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(docker.stopContainerCmd("runtime")).thenReturn(stop);
        when(docker.removeContainerCmd("runtime")).thenReturn(remove);
        doThrow(conflict).when(remove).exec();
        when(docker.inspectContainerCmd("runtime")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState().getRunning()).thenReturn(running);
        when(docker.waitContainerCmd("runtime")).thenReturn(wait);
    }

    @ParameterizedTest
    @ValueSource(strings = {"already-absent", "completed", "registration-missed", "transport-failed"})
    void confirmedAbsenceClosesLogsAfterConcurrentRemoval(String outcome) throws Exception {
        removalConflict(false);
        if ("already-absent".equals(outcome)) {
            when(inspect.exec()).thenThrow(new NotFoundException("removed"));
        } else {
            when(wait.exec(any(WaitContainerResultCallback.class))).thenAnswer(call -> {
                verify(logs, never()).close();
                when(inspect.exec()).thenThrow(new NotFoundException("removed"));
                if ("registration-missed".equals(outcome)) {
                    throw new NotFoundException("removed before wait registered");
                }
                WaitContainerResultCallback callback = call.getArgument(0);
                callback.onStart(waitStream);
                if ("transport-failed".equals(outcome)) {
                    callback.onError(new DockerException("wait response lost", 500));
                } else {
                    callback.onComplete();
                }
                return callback;
            });
        }

        manager.stopAndRemoveStrict("runtime", logs);

        verify(logs).close();
        if ("already-absent".equals(outcome)) {
            verify(docker, never()).waitContainerCmd(anyString());
        } else {
            verify(wait).withCondition(WaitContainerCondition.REMOVED);
            verify(inspect, times(2)).exec();
            if (!"registration-missed".equals(outcome)) {
                verify(waitStream, atLeastOnce()).close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "stopped", "unknown", "timeout", "interrupted"})
    void unconfirmedRemovalRetainsLogsAndTheOriginalFailure(String outcome) throws Exception {
        removalConflict("running".equals(outcome));
        if ("unknown".equals(outcome)) {
            when(inspect.exec()).thenThrow(new DockerException("inspection unavailable", 500));
        }
        AtomicReference<WaitContainerResultCallback> pending = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        when(wait.exec(any(WaitContainerResultCallback.class))).thenAnswer(call -> {
            WaitContainerResultCallback callback = call.getArgument(0);
            callback.onStart(waitStream);
            pending.set(callback);
            registered.countDown();
            if ("interrupted".equals(outcome)) {
                Thread.currentThread().interrupt();
            } else if (!"timeout".equals(outcome)) {
                callback.onComplete();
            }
            return callback;
        });

        try {
            IllegalStateException failure = "timeout".equals(outcome)
                    ? completeTimedWait(pending, registered)
                    : assertThrows(IllegalStateException.class, () -> manager.stopAndRemoveStrict("runtime", logs));
            assertSame(conflict, failure.getCause());
            assertEquals("interrupted".equals(outcome), Thread.currentThread().isInterrupted());
            verify(logs, never()).close();
            verify(waitStream, atLeastOnce()).close();
        } finally {
            Thread.interrupted();
        }
    }

    private IllegalStateException completeTimedWait(AtomicReference<WaitContainerResultCallback> pending,
                                                   CountDownLatch registered) throws Exception {
        FutureTask<IllegalStateException> task = new FutureTask<>(() ->
                assertThrows(IllegalStateException.class, () -> manager.stopAndRemoveStrict("runtime", logs)));
        Thread teardown = Thread.ofPlatform().start(task);
        try {
            assertTrue(registered.await(5, TimeUnit.SECONDS), "removal callback registered");
            ThreadStates.awaitState(teardown, EnumSet.of(Thread.State.TIMED_WAITING),
                    Duration.ofSeconds(5), "bounded removal wait");
            pending.get().onComplete();
            return task.get(5, TimeUnit.SECONDS);
        } finally {
            WaitContainerResultCallback callback = pending.get();
            if (callback != null) {
                callback.onComplete();
            }
            teardown.interrupt();
            teardown.join(Duration.ofSeconds(5));
            assertFalse(teardown.isAlive(), "teardown worker stopped");
        }
    }
}
