package io.floci.aws.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTestTaskTest {

    @Test
    void taskReportsIoFailureToOwningTest() {
        IOException failure = new IOException("mock driver failed");
        ProxyTestTask<Void> driver = ProxyTestTask.start(() -> {
            throw failure;
        });
        assertSame(failure, assertThrows(ExecutionException.class, driver::await).getCause());
        assertFalse(driver.thread().isAlive());
    }

    @Test
    void taskReportsAssertionFailureToOwningTest() {
        AssertionError failure = new AssertionError("mock driver assertion failed");
        ProxyTestTask<Void> driver = ProxyTestTask.start(() -> {
            throw failure;
        });
        assertSame(failure, assertThrows(ExecutionException.class, driver::await).getCause());
        assertFalse(driver.thread().isAlive());
    }

    @Test
    void taskCompletesOnVirtualThread() throws Exception {
        try (ProxyTestTask<Boolean> driver = ProxyTestTask.start(() -> Thread.currentThread().isVirtual())) {
            assertTrue(driver.await());
        }
    }
}
