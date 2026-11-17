package com.floci.test;

import org.opentest4j.TestAbortedException;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Runs teardown actions, preserving test failures and surfacing cleanup failures after an abort. */
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
        if (failure != null && !(failure instanceof TestAbortedException)) {
            errors.forEach(failure::addSuppressed);
            return;
        }
        RuntimeException aggregate = new RuntimeException("Test fixture cleanup failed", failure);
        errors.forEach(aggregate::addSuppressed);
        throw aggregate;
    }
}
