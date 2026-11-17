package com.floci.test;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FixtureCleanupTest {

    @Test
    void runsInReverseRegistrationOrderAndDrainsActions() {
        List<String> completed = new ArrayList<>();
        Deque<Runnable> actions = new ArrayDeque<>();
        actions.push(() -> completed.add("client"));
        actions.push(() -> completed.add("network"));
        actions.push(() -> completed.add("task"));

        FixtureCleanup.run(actions, null);
        FixtureCleanup.run(actions, null);

        assertThat(completed).containsExactly("task", "network", "client");
        assertThat(actions).isEmpty();
    }

    @Test
    void attemptsEveryActionAndReportsAllCleanupFailures() {
        List<String> completed = new ArrayList<>();
        RuntimeException taskFailure = new IllegalStateException("task cleanup failed");
        RuntimeException networkFailure = new IllegalStateException("network cleanup failed");
        Deque<Runnable> actions = new ArrayDeque<>();
        actions.push(() -> completed.add("client"));
        actions.push(() -> {
            completed.add("network");
            throw networkFailure;
        });
        actions.push(() -> {
            completed.add("task");
            throw taskFailure;
        });

        Throwable failure = catchThrowable(() -> FixtureCleanup.run(actions, null));

        assertThat(completed).containsExactly("task", "network", "client");
        assertThat(actions).isEmpty();
        assertThat(failure).isInstanceOf(RuntimeException.class)
                .hasMessage("Test fixture cleanup failed");
        assertThat(failure.getSuppressed()).containsExactly(taskFailure, networkFailure);
    }

    @Test
    void preservesTheTestFailureAndAddsEveryCleanupFailure() {
        AssertionError testFailure = new AssertionError("test failed");
        RuntimeException existingFailure = new IllegalStateException("earlier cleanup failed");
        RuntimeException firstFailure = new IllegalStateException("task cleanup failed");
        RuntimeException secondFailure = new IllegalStateException("network cleanup failed");
        testFailure.addSuppressed(existingFailure);
        Deque<Runnable> actions = new ArrayDeque<>();
        actions.push(() -> {
            throw secondFailure;
        });
        actions.push(() -> {
            throw firstFailure;
        });

        FixtureCleanup.run(actions, testFailure);

        assertThat(actions).isEmpty();
        assertThat(testFailure.getSuppressed()).containsExactly(existingFailure, firstFailure, secondFailure);
    }
}
