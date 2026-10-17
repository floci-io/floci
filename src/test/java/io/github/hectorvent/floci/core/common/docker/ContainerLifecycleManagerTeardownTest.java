package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerTeardownTest {

    @Test
    void cancellationBeforeAdmissionLeavesContainerUntouchedAndReleasesNoOtherSlot() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null, null);
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(stop);
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
        AtomicReference<Throwable> cancellation = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch requestingSlot = new CountDownLatch(1);
        Thread waiter = null;
        try {
            assertTrue(removing.await(10, TimeUnit.SECONDS));
            waiter = Thread.ofVirtual().start(() -> {
                try {
                    requestingSlot.countDown();
                    manager.stopAndRemoveStrict("waiting", null);
                } catch (Throwable problem) {
                    cancellation.set(problem);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertTrue(requestingSlot.await(10, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(10000);
            assertFalse(waiter.isAlive());
            assertInstanceOf(InterruptedException.class, cancellation.get().getCause());
            assertTrue(interrupted.get());
            verify(docker, never()).stopContainerCmd("waiting");
            verify(docker, never()).removeContainerCmd("waiting");
        } finally {
            if (waiter != null) {
                waiter.interrupt();
                waiter.join(10000);
            }
            release.countDown();
            for (Thread owner : owners) {
                owner.join(10000);
                assertFalse(owner.isAlive());
            }
        }
        assertNull(failure.get());
        // A retained owner can retry after cancellation; all admitted operations released slots.
        manager.stopAndRemoveStrict("waiting", null);
        verify(docker).removeContainerCmd("waiting");
    }

    @Test
    void removalFailuresReleaseAdmissionForLaterOwners() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null, null);
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

}
