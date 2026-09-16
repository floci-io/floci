package io.github.hectorvent.floci.core.common.port;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortAllocatorTest {

    @Test
    void allocatesSequentiallyFromBase() {
        PortAllocator allocator = new PortAllocator(9200, 9299);
        assertEquals(9200, allocator.allocate());
        assertEquals(9201, allocator.allocate());
        assertEquals(9202, allocator.allocate());
    }

    @Test
    void concurrentAllocationsAreUnique() throws InterruptedException {
        PortAllocator allocator = new PortAllocator(9200, 9299);
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
        PortAllocator allocator = new PortAllocator(9200, 9209);
        Set<Integer> handed = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            assertTrue(handed.add(allocator.allocate()));
        }
        assertThrows(IllegalStateException.class, allocator::allocate);
    }

    @Test
    void exhaustionMessageNamesThePoolAndTheWideningProperty() {
        PortAllocator allocator = new PortAllocator(9200, 9200);
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
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add);

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
        PortAllocator allocator = new PortAllocator(9200, 9209, warnings::add);
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
        PortAllocator allocator = new PortAllocator(9200, 9201);
        int first = allocator.allocate();
        allocator.allocate();
        assertThrows(IllegalStateException.class, allocator::allocate);

        allocator.release(first);
        assertEquals(first, allocator.allocate());
    }
}
