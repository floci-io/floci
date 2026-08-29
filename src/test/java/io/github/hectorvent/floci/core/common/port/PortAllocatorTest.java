package io.github.hectorvent.floci.core.common.port;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * When the pool runs dry the exception is often the only artefact an operator ever sees —
     * it reaches them second-hand as a CloudFormation rollback, with floci's own logs the only
     * place the cause survives (issue #2206). "No free ports in range 9200-9299" does not say
     * which pool ran dry or how to widen it, leaving the reader to find PortAllocator in the
     * source to discover the knob exists. The message must carry its own diagnosis.
     */
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
    void releasedPortBecomesAvailableAgain() {
        PortAllocator allocator = new PortAllocator(9200, 9201);
        int first = allocator.allocate();
        allocator.allocate();
        assertThrows(IllegalStateException.class, allocator::allocate);

        allocator.release(first);
        assertEquals(first, allocator.allocate());
    }
}
