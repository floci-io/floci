package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerTeardownsTest {

    private static final Logger LOG = Logger.getLogger(ContainerTeardownsTest.class);

    @Test
    void stopAllRunsATeardownWhoseContainersOthersUseAfterTheirs() {
        List<String> stopped = new ArrayList<>();
        ContainerTeardown storage = new ContainerTeardown() {
            @Override
            public void stopManagedContainers() {
                stopped.add("storage");
            }

            @Override
            public int teardownOrder() {
                return 1;
            }
        };
        ContainerTeardown drivers = () -> stopped.add("drivers");
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(invocation -> List.of(storage, drivers).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        assertEquals(List.of("drivers", "storage"), stopped);
    }

    @Test
    void admitsEveryOwnedStopBeforeWaitingForOtherContainers() throws Exception {
        List<Integer> resources = IntStream.range(0, 83).boxed().toList();
        CountDownLatch started = new CountDownLatch(resources.size());
        CountDownLatch release = new CountDownLatch(1);
        Set<Integer> stopped = ConcurrentHashMap.newKeySet();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> drain = caller.submit(() -> ContainerTeardowns.stopInParallel(resources, resource -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            stopped.add(resource);
        }, LOG));
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS),
                    "Queued containers must receive their stop before slower containers consume the shared deadline");
        } finally {
            release.countDown();
            try {
                drain.get(5, TimeUnit.SECONDS);
            } finally {
                caller.shutdownNow();
            }
        }
        assertEquals(Set.copyOf(resources), stopped);
    }

    @Test
    void failedStopDoesNotPreventTheOtherOwnedStops() {
        List<Integer> resources = IntStream.range(0, 83).boxed().toList();
        Set<Integer> attempted = ConcurrentHashMap.newKeySet();
        ContainerTeardowns.stopInParallel(resources, resource -> {
            attempted.add(resource);
            if (resource == 0) {
                throw new IllegalStateException("Docker removal failed");
            }
        }, LOG);
        assertEquals(Set.copyOf(resources), attempted);
    }

    @Test
    void anExpiredDeadlineDoesNotStartAnotherDrainBudget() {
        AtomicInteger attempts = new AtomicInteger();
        ContainerTeardowns.stopInParallel(List.of(1, 2),
                ignored -> attempts.incrementAndGet(), LOG, System.nanoTime() - 1);
        assertEquals(0, attempts.get());
    }
}
