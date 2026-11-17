package com.floci.test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Runs registered teardown actions while retaining the original test failure. */
final class FixtureCleanup {

    private FixtureCleanup() {
    }

    static void run(Deque<Runnable> actions, Throwable failure) {
        List<RuntimeException> errors = new ArrayList<>();
        while (!actions.isEmpty()) {
            try {
                actions.pop().run();
            } catch (RuntimeException error) {
                errors.add(error);
            }
        }
        if (errors.isEmpty()) {
            return;
        }
        if (failure != null) {
            errors.forEach(failure::addSuppressed);
            return;
        }
        RuntimeException aggregate = new RuntimeException("Test fixture cleanup failed");
        errors.forEach(aggregate::addSuppressed);
        throw aggregate;
    }
}
