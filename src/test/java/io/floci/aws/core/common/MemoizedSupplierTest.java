package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class MemoizedSupplierTest {

    @Test
    void resolvesLazilyAndReusesTheProviderResult() {
        AtomicInteger resolutions = new AtomicInteger();
        Object value = new Object();
        MemoizedSupplier<Object> supplier = new MemoizedSupplier<>(() -> {
            resolutions.incrementAndGet();
            return value;
        });
        assertEquals(0, resolutions.get());
        assertSame(value, supplier.get());
        assertSame(value, supplier.get());
        assertEquals(1, resolutions.get());
    }

    @Test
    void retriesAfterUnavailableRuntimeMapping() {
        AtomicInteger resolutions = new AtomicInteger();
        Object value = new Object();
        MemoizedSupplier<Object> supplier = new MemoizedSupplier<>(() -> {
            if (resolutions.incrementAndGet() == 1) {
                throw new IllegalStateException("Runtime mapping is not available yet");
            }
            return value;
        });
        assertThrows(IllegalStateException.class, supplier::get);
        assertSame(value, supplier.get());
        assertSame(value, supplier.get());
        assertEquals(2, resolutions.get());
    }

    @Test
    void retriesNullWithoutCachingAbsence() {
        AtomicInteger resolutions = new AtomicInteger();
        Object value = new Object();
        MemoizedSupplier<Object> supplier = new MemoizedSupplier<>(() ->
                resolutions.incrementAndGet() == 1 ? null : value);
        assertNull(supplier.get());
        assertSame(value, supplier.get());
        assertSame(value, supplier.get());
        assertEquals(2, resolutions.get());
    }

    @Test
    void cachesProxyWhileReadingTheCallingThreadsContext() throws Exception {
        ThreadLocal<String> account = new ThreadLocal<>();
        AtomicInteger resolutions = new AtomicInteger();
        Supplier<String> proxy = account::get;
        MemoizedSupplier<Supplier<String>> supplier = new MemoizedSupplier<>(() -> {
            resolutions.incrementAndGet();
            return proxy;
        });
        account.set("owner");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertEquals("owner", supplier.get().get());
            Future<String> foreign = executor.submit(() -> {
                account.set("foreign");
                try {
                    return supplier.get().get();
                } finally {
                    account.remove();
                }
            });
            assertEquals("foreign", foreign.get());
            assertEquals("owner", supplier.get().get());
            account.remove();
            assertNull(supplier.get().get());
            assertEquals(1, resolutions.get());
        } finally {
            account.remove();
        }
    }
}
