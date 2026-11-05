package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StripedLocksTest {

    @Test
    void equalKeysShareAMonitor() {
        StripedLocks locks = new StripedLocks(256);

        assertSame(locks.lockFor("runtime"), locks.lockFor(new String("runtime")));
    }

    @Test
    void aSingleStripeReusesItsMonitorForEveryKey() {
        StripedLocks locks = new StripedLocks(1);

        assertSame(locks.lockFor("first"), locks.lockFor("second"));
    }

    @Test
    void distinctStripesHaveDifferentMonitors() {
        StripedLocks locks = new StripedLocks(4);

        assertNotSame(locks.lockFor(0), locks.lockFor(1));
    }

    @Test
    void negativeHashesSelectANonnegativeStripe() {
        StripedLocks locks = new StripedLocks(3);

        assertSame(locks.lockFor(Integer.MIN_VALUE), locks.lockFor(1));
        assertSame(locks.lockFor(-1), locks.lockFor(2));
    }

    @Test
    void requiresAPositiveStripeCount() {
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(0));
        assertThrows(IllegalArgumentException.class, () -> new StripedLocks(-1));
    }
}
