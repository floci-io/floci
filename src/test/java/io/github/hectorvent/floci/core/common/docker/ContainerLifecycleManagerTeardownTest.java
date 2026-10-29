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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
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

    @Test
    void interruptedWaitersStillCleanUpWithoutReleasingAnotherOwnersSlot() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stop);
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(anyString())).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(0).startsWith("owned-")) {
                return remove;
            }
            RemoveContainerCmd waitingRemove = mock(RemoveContainerCmd.class);
            when(waitingRemove.withForce(true)).thenReturn(waitingRemove);
            return waitingRemove;
        });
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
            boolean strict = i % 2 == 0;
            owners.add(Thread.ofVirtual().start(() -> {
                try {
                    if (strict) {
                        manager.stopAndRemoveStrict(id, null);
                    } else {
                        manager.stopAndRemove(id, null, 1);
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }));
        }
        List<Thread> waiters = new ArrayList<>();
        List<AtomicBoolean> interrupted = new ArrayList<>();
        try {
            assertTrue(removing.await(10, TimeUnit.SECONDS));
            for (boolean strict : List.of(false, true)) {
                String id = "waiting-" + strict;
                AtomicBoolean preservedInterrupt = new AtomicBoolean();
                interrupted.add(preservedInterrupt);
                Thread waiter = Thread.ofVirtual().start(() -> {
                    try {
                        if (strict) {
                            manager.stopAndRemoveStrict(id, null);
                        } else {
                            manager.stopAndRemove(id, null);
                        }
                        preservedInterrupt.set(Thread.currentThread().isInterrupted());
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                });
                waiters.add(waiter);
                awaitBlockedOrDone(waiter);
                waiter.interrupt();
                assertTrue(waiter.isAlive());
                verify(docker, never()).stopContainerCmd(id);
                verify(docker, never()).removeContainerCmd(id);
            }
        } finally {
            release.countDown();
            for (Thread thread : owners) {
                thread.join(10000);
                assertFalse(thread.isAlive());
            }
            for (Thread thread : waiters) {
                thread.join(10000);
                assertFalse(thread.isAlive());
            }
        }
        assertNull(failure.get());
        for (AtomicBoolean preservedInterrupt : interrupted) {
            assertTrue(preservedInterrupt.get());
        }
        for (boolean strict : List.of(false, true)) {
            verify(docker).stopContainerCmd("waiting-" + strict);
            verify(docker).removeContainerCmd("waiting-" + strict);
        }
    }

    @Test
    void removalFailuresReleaseAdmissionForLaterOwners() throws Exception {
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
