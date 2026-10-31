package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerTeardownTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictRemovalRetainsLogStreamForRetryWithOptionalAnonymousVolumeCleanup(boolean removeAnonymousVolumes)
            throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd("retry")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("retry")).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        doThrow(new IllegalStateException("daemon refused removal")).doNothing().when(remove).exec();
        Closeable logStream = mock(Closeable.class);
        Runnable teardown = removeAnonymousVolumes
                ? () -> manager.stopAndRemoveStrict("retry", logStream, 5, true)
                : () -> manager.stopAndRemoveStrict("retry", logStream);

        assertThrows(IllegalStateException.class, teardown::run);
        verify(logStream, never()).close();
        if (removeAnonymousVolumes) {
            verify(remove).withRemoveVolumes(true);
        } else {
            verify(remove, never()).withRemoveVolumes(true);
        }
        teardown.run();
        verify(logStream).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"strict", "strict-volumes", "best-effort", "stop-only"})
    void laterOwnedStopsReachDockerWhileEarlierRemovalsAreBlocked(String mode) throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        CountDownLatch ninthStop = new CountDownLatch(1);
        when(docker.stopContainerCmd(anyString())).thenAnswer(invocation -> {
            if ("ninth".equals(invocation.getArgument(0))) {
                ninthStop.countDown();
            }
            return stop;
        });
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(anyString())).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        CountDownLatch removing = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        when(remove.exec()).thenAnswer(invocation -> {
            removing.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return null;
        });
        List<Thread> owners = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < 8; i++) {
            String id = "owned-" + i;
            owners.add(Thread.ofVirtual().start(() -> {
                try {
                    manager.stopAndRemoveStrict(id, null);
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }));
        }
        try {
            assertTrue(removing.await(10, TimeUnit.SECONDS));
            owners.add(Thread.ofVirtual().start(() -> {
                try {
                    if ("strict".equals(mode)) {
                        manager.stopAndRemoveStrict("ninth", null);
                    } else if ("strict-volumes".equals(mode)) {
                        manager.stopAndRemoveStrict("ninth", null, 5, true);
                    } else if ("stop-only".equals(mode)) {
                        manager.stop("ninth", 5);
                    } else {
                        manager.stopAndRemove("ninth", null);
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }));
            assertTrue(ninthStop.await(3, TimeUnit.SECONDS),
                    "Slow removals must not queue another owned container's stop past the shared drain budget");
        } finally {
            release.countDown();
            for (Thread thread : owners) {
                thread.join(10000);
                assertFalse(thread.isAlive());
            }
        }
        assertNull(failure.get());
        verify(docker).stopContainerCmd("ninth");
        if ("stop-only".equals(mode)) {
            verify(docker, never()).removeContainerCmd("ninth");
        } else {
            verify(docker).removeContainerCmd("ninth");
        }
    }

    @Test
    void removalFailuresDoNotPreventLaterOwnedCleanup() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stop);
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(anyString())).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        when(remove.exec()).thenThrow(new IllegalStateException("daemon refused removal"));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            executor.submit(() -> {
                for (int i = 0; i < 16; i++) {
                    String id = "failed-" + i;
                    assertThrows(IllegalStateException.class,
                            () -> manager.stopAndRemoveStrict(id, null));
                }
            }).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aContainerThatHadAlreadyExitedCountsAsStopped() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stop);
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        // Docker answers a stop of an exited container with HTTP 304.
        when(stop.exec()).thenThrow(new NotModifiedException("Container already stopped"));
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(anyString())).thenReturn(remove);
        when(remove.withForce(true)).thenReturn(remove);
        when(remove.exec()).thenThrow(new IllegalStateException("daemon refused removal"));
        Closeable logStream = mock(Closeable.class);

        manager.stopAndRemove("exited", logStream, 0);

        verify(logStream).close();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.stopAndRemoveStrict("exited-strict", null));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void aStopLeavesTheContainerInPlaceAndCountsAnExitedOrMissingOneAsStopped() {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stop);
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        doAnswer(invocation -> null)
                .doThrow(new NotModifiedException("Container already stopped"))
                .doThrow(new NotFoundException("No such container"))
                .when(stop).exec();

        manager.stop("running", 0);
        manager.stop("exited", 0);
        manager.stop("gone", 0);

        verify(stop, times(3)).exec();
        verify(docker, never()).removeContainerCmd(anyString());
    }

}
