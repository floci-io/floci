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
        for (int i = 0; i < 11; i++) {
            try {
                int p = allocator.allocate();
                assertTrue(handed.add(p), "allocate() returned port " + p + " already in use");
            } catch (RuntimeException exhausted) {
                return;
            }
        }
    }
}
