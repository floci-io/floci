package io.github.hectorvent.floci.core.common;

/** A bounded set of monitors for callers to synchronize operations sharing a key. */
public final class StripedLocks {

    private final Object[] locks;

    public StripedLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }
        locks = new Object[stripeCount];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public Object lockFor(Object key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
