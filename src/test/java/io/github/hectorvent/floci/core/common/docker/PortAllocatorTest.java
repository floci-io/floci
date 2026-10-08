package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PortAllocatorTest {

    @Test
    void allocatesPortInRange() {
        PortAllocator allocator = new PortAllocator();
        // Use a high ephemeral range unlikely to conflict with real services
        int port = allocator.allocate(19900, 19999);
        assertTrue(port >= 19900 && port <= 19999, "Port should be within range");
        allocator.release(port);
    }

    @Test
    void reservedPortIsSkippedByNextCaller() {
        PortAllocator allocator = new PortAllocator();
        int first = allocator.allocate(19900, 19999);
        int second = allocator.allocate(19900, 19999);
        assertNotEquals(first, second, "Two sequential allocations must return different ports");
        allocator.release(first);
        allocator.release(second);
    }

    @Test
    void releasedPortBecomesAvailableAgain() {
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocate(19900, 19999);
        allocator.release(port);
        // After release the same port can be re-allocated
        int reused = allocator.allocate(19900, 19999);
        assertEquals(port, reused, "Released port should be the first candidate again");
        allocator.release(reused);
    }

    @Test
    void concurrentAllocationsAreUnique() throws Exception {
        PortAllocator allocator = new PortAllocator();
        int threads = 20;
        // Range must be at least as wide as the thread count
        int base = 19900;
        int max = base + threads - 1;

        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                int p = allocator.allocate(base, max);
                ports.add(p);
            }));
        }

        ready.await();   // all threads are lined up
        go.countDown();  // release them simultaneously

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertEquals(threads, ports.size(),
                "Concurrent allocations must all return unique ports; got duplicates: " + ports);

        ports.forEach(allocator::release);
    }

    @Test
    void throwsWhenRangeExhausted() {
        PortAllocator allocator = new PortAllocator();
        // Allocate the only port in a single-port range
        int port = allocator.allocate(19900, 19900);
        // Now the range is full — next call must throw
        assertThrows(RuntimeException.class, () -> allocator.allocate(19900, 19900));
        allocator.release(port);
    }

    @Test
    void allocateAndStartMovesPastPortsDockerReportsInUseAndReleasesThem() {
        PortAllocator allocator = new HostBlindPortAllocator();
        List<Integer> tried = new ArrayList<>();

        int started = allocator.allocateAndStart(19900, 19902, port -> {
            tried.add(port);
            if (port < 19902) {
                throw new RuntimeException("Bind for 127.0.0.1:" + port + " failed: port is already allocated");
            }
            return port;
        });

        assertEquals(19902, started);
        assertEquals(List.of(19900, 19901, 19902), tried);
        assertEquals(19900, allocator.allocate(19900, 19902), "refused ports must be released");
        assertEquals(19901, allocator.allocate(19900, 19902), "refused ports must be released");
        assertThrows(RuntimeException.class, () -> allocator.allocate(19902, 19902),
                "the port the start succeeded on must stay reserved");
    }

    @Test
    void allocateAndStartNamesTheRefusedPortsWhenTheRangeRunsOut() {
        PortAllocator allocator = new HostBlindPortAllocator();

        RuntimeException e = assertThrows(RuntimeException.class, () -> allocator.allocateAndStart(19900, 19901,
                port -> {
                    throw new RuntimeException(new IOException("listen tcp4 0.0.0.0:" + port
                            + ": bind: address already in use"));
                }));

        assertEquals("Docker reports host ports [19900, 19901] already in use and no other port in 19900-19901"
                + " is free", e.getMessage());
        assertEquals(19900, allocator.allocate(19900, 19901), "refused ports must be released");
        assertEquals(19901, allocator.allocate(19900, 19901), "refused ports must be released");
    }

    @Test
    void allocateAndStartReleasesThePortAndRethrowsAnyOtherFailure() {
        PortAllocator allocator = new HostBlindPortAllocator();
        RuntimeException failure = new RuntimeException("Cannot connect to the Docker daemon");
        List<Integer> tried = new ArrayList<>();

        RuntimeException e = assertThrows(RuntimeException.class, () -> allocator.allocateAndStart(19900, 19901,
                port -> {
                    tried.add(port);
                    throw failure;
                }));

        assertSame(failure, e);
        assertEquals(List.of(19900), tried);
        assertEquals(19900, allocator.allocate(19900, 19901), "the chosen port must be released");
    }
}
