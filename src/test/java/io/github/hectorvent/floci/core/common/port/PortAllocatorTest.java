package io.github.hectorvent.floci.core.common.port;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PortAllocatorTest {

    // Pool bookkeeping is asserted against a host where every port is free, so these tests do
    // not depend on what is listening on the machine running them (9200 is OpenSearch's port).
    private static final IntPredicate ALWAYS_BINDABLE = p -> true;

    @Test
    void allocatesSequentiallyFromBase() {
        PortAllocator allocator = new PortAllocator(9200, 9299, w -> { }, ALWAYS_BINDABLE);
        assertEquals(9200, allocator.allocate());
        assertEquals(9201, allocator.allocate());
        assertEquals(9202, allocator.allocate());
    }

    @Test
    void concurrentAllocationsAreUnique() throws InterruptedException {
        PortAllocator allocator = new PortAllocator(9200, 9299, w -> { }, ALWAYS_BINDABLE);
        int threads = 50;
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ports.add(allocator.allocate());
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(threads, ports.size(), "All allocated ports must be unique");
    }

    @Test
    void allocateNeverReturnsPortAlreadyHandedOut() {
        PortAllocator allocator = new PortAllocator(9200, 9209, w -> { }, ALWAYS_BINDABLE);
        Set<Integer> handed = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            assertTrue(handed.add(allocator.allocate()));
        }
        assertThrows(IllegalStateException.class, allocator::allocate);
    }

    @Test
    void exhaustionMessageNamesThePoolAndTheWideningProperty() {
        PortAllocator allocator = new PortAllocator(9200, 9200, w -> { }, ALWAYS_BINDABLE);
        allocator.allocate();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, allocator::allocate);
        String message = thrown.getMessage();

        assertTrue(message.contains("Lambda Runtime API"),
                "message must name the pool that ran dry; got: " + message);
        assertTrue(message.contains("floci.services.lambda.runtime-api-max-port"),
                "message must name the property that widens the pool; got: " + message);
        assertTrue(message.contains("9200"),
                "message must still report the exhausted range; got: " + message);
    }

    @Test
    void warnsOnceWhenPoolCrossesNinetyPercent() {
        List<String> warnings = new ArrayList<>();
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add, ALWAYS_BINDABLE);

        for (int i = 0; i < 10; i++) {
            allocator.allocate();
        }

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("90% allocated"));
        assertTrue(warnings.getFirst().contains("9/10 ports"));
        assertTrue(warnings.getFirst().contains("runtime-api-max-port"));
    }

    @Test
    void warningRearmsAfterPressureDropsBelowThreshold() {
        List<String> warnings = new ArrayList<>();
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add, ALWAYS_BINDABLE);
        List<Integer> ports = new ArrayList<>();

        for (int i = 0; i < 9; i++) {
            ports.add(allocator.allocate());
        }
        allocator.release(ports.getLast());
        ports.removeLast();
        ports.add(allocator.allocate());

        assertEquals(2, warnings.size());
    }

    @Test
    void releasedPortBecomesAvailableAgain() {
        PortAllocator allocator = new PortAllocator(9200, 9201, w -> { }, ALWAYS_BINDABLE);
        int first = allocator.allocate();
        allocator.allocate();
        assertThrows(IllegalStateException.class, allocator::allocate);

        allocator.release(first);
        assertEquals(first, allocator.allocate());
    }

    @Test
    void skipsPortHeldByAnotherProcessAndReusesItOnceReleased() throws IOException {
        // Holding the base port from outside the allocator stands in for a second floci (or any
        // other process) in the same network namespace. Handing it out would leave the Runtime
        // API server retrying a bind it can never win while the rest of the range sits idle.
        int held;
        PortAllocator allocator;
        try (ServerSocket holder = new ServerSocket(0)) {
            held = holder.getLocalPort();
            assumeTrue(held <= 65535 - 50, "OS picked a port too close to 65535 for the range");
            allocator = new PortAllocator(held, held + 50);
            int first = allocator.allocate();
            assertNotEquals(held, first, "allocate() must skip a port another process holds");
            assertTrue(first > held && first <= held + 50);
        }

        assertEquals(held, allocator.allocate(),
                "a port skipped while held must be handed out once its holder lets go");
    }
}
