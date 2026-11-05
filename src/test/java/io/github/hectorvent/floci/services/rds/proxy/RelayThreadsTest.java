package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayThreadsTest {

    @Test
    void relaysRunOnNamedDaemonPlatformThreads() throws InterruptedException {
        AtomicReference<Thread> ran = new AtomicReference<>();

        Thread relay = RelayThreads.start("rds-test-relay", () -> ran.set(Thread.currentThread()));
        relay.join(5_000);

        assertEquals(relay, ran.get());
        assertFalse(relay.isVirtual(), "a relay woken per message must not pay the virtual-thread wakeup");
        assertTrue(relay.isDaemon(), "a relay must not keep the JVM alive");
        assertEquals("rds-test-relay", relay.getName());
    }
}
