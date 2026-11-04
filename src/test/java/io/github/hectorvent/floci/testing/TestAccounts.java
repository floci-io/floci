package io.github.hectorvent.floci.testing;

import java.util.concurrent.ThreadLocalRandom;

/** Random account IDs for tests with isolated account state. */
public final class TestAccounts {

    private TestAccounts() {
    }

    public static String randomId() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
    }
}
