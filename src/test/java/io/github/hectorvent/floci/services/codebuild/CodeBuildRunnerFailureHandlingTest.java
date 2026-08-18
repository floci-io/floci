package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No failure class may leave a Build IN_PROGRESS forever: CodePipeline actions poll
 * buildComplete, so a build thread killed by an Error (e.g. OutOfMemoryError) must
 * still end the build as FAILED with an error context.
 */
class CodeBuildRunnerFailureHandlingTest {

    private CodeBuildRunner runner() {
        return new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void errorInBuildThreadEndsBuildFailedInsteadOfWedgingInProgress() throws Exception {
        Build build = new Build();
        build.setId("oom-build:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        AtomicBoolean thrown = new AtomicBoolean();
        build.setPhases(new CopyOnWriteArrayList<>() {
            @Override
            public boolean add(BuildPhase phase) {
                if (thrown.compareAndSet(false, true)) {
                    throw new OutOfMemoryError("simulated allocation failure");
                }
                return super.add(phase);
            }
        });

        runner().startBuild("us-east-1", build, new Project(), "version: 0.2");

        long deadline = System.currentTimeMillis() + 10_000;
        while (!Boolean.TRUE.equals(build.getBuildComplete()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(Boolean.TRUE, build.getBuildComplete());
        assertEquals("FAILED", build.getBuildStatus());
        assertEquals("COMPLETED", build.getCurrentPhase());
        assertNotNull(build.getEndTime());
        BuildPhase completed = build.getPhases().stream()
                .filter(p -> "COMPLETED".equals(p.getPhaseType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing COMPLETED phase: " + build.getPhases()));
        assertEquals("FAILED", completed.getPhaseStatus());
        List<Map<String, String>> contexts = completed.getContexts();
        assertNotNull(contexts);
        assertEquals("FAULT_ERROR", contexts.get(0).get("statusCode"));
        assertTrue(contexts.get(0).get("message").contains("OutOfMemoryError"), contexts.get(0).get("message"));
        assertTrue(contexts.get(0).get("message").contains("simulated allocation failure"), contexts.get(0).get("message"));
    }

    @Test
    void uncaughtErrorFailsTheInProgressPhaseWithContext() {
        Build build = new Build();
        build.setId("late-error:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setPhases(new CopyOnWriteArrayList<>());
        BuildPhase inProgress = new BuildPhase();
        inProgress.setPhaseType("BUILD");
        inProgress.setPhaseStatus("IN_PROGRESS");
        inProgress.setStartTime(System.currentTimeMillis() / 1000.0);
        build.getPhases().add(inProgress);

        runner().failBuildOnUncaughtError(build, new OutOfMemoryError("Java heap space"));

        assertEquals(Boolean.TRUE, build.getBuildComplete());
        assertEquals("FAILED", build.getBuildStatus());
        assertEquals("FAILED", inProgress.getPhaseStatus());
        assertNotNull(inProgress.getEndTime());
        assertTrue(inProgress.getContexts().get(0).get("message").contains("Java heap space"),
                inProgress.getContexts().toString());
    }

    @Test
    void completedBuildIsLeftUntouchedByTheUncaughtErrorNet() {
        Build build = new Build();
        build.setId("done-build:1");
        build.setBuildStatus("SUCCEEDED");
        build.setBuildComplete(true);
        build.setCurrentPhase("COMPLETED");
        build.setPhases(new CopyOnWriteArrayList<>());

        runner().failBuildOnUncaughtError(build, new IllegalStateException("late cleanup failure"));

        assertEquals("SUCCEEDED", build.getBuildStatus());
        assertTrue(build.getPhases().isEmpty());
    }

    // Regression coverage for the Prepare-stage "phantom success" race: a build whose
    // exec-attach stream never delivered any output (no phase-start sentinel ever
    // parsed) must never be reported SUCCEEDED just because inspectExecCmd returned a
    // clean exit code for an exec that may never have actually run.

    @Test
    void zeroOutputSessionIsFailedEvenWithACleanExitCode() {
        Build build = new Build();
        build.setId("phantom-build:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setPhases(new CopyOnWriteArrayList<>());

        CodeBuildRunner.PhaseSession session = runner().newPhaseSession(build, "lg", "ls", "us-east-1");

        boolean anyPhaseFailed = session.finish(0L, null);

        assertTrue(anyPhaseFailed, "a build with zero phase output must never be reported as a clean success");
        BuildPhase install = build.getPhases().stream()
                .filter(p -> "INSTALL".equals(p.getPhaseType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing INSTALL phase: " + build.getPhases()));
        assertEquals("FAILED", install.getPhaseStatus());
        assertTrue(install.getContexts().get(0).get("message").contains("no phase ever started"),
                install.getContexts().toString());
        // The remaining phases are attributed as skipped rather than each re-reporting
        // the same session-level failure.
        assertEquals(4, build.getPhases().size());
    }

    @Test
    void execAttachErrorIsReportedAsFailureNotSwallowed() {
        Build build = new Build();
        build.setId("attach-error-build:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setPhases(new CopyOnWriteArrayList<>());

        CodeBuildRunner.PhaseSession session = runner().newPhaseSession(build, "lg", "ls", "us-east-1");

        // Simulates runPhaseSession's onError() capturing the exec-attach exception and
        // passing it through as the session error, instead of discarding it and letting
        // a stale/default exit-code inspection decide the outcome.
        boolean anyPhaseFailed = session.finish(null, "Phase exec attach failed: connection reset");

        assertTrue(anyPhaseFailed);
        BuildPhase install = build.getPhases().stream()
                .filter(p -> "INSTALL".equals(p.getPhaseType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing INSTALL phase: " + build.getPhases()));
        assertEquals("FAILED", install.getPhaseStatus());
        assertTrue(install.getContexts().get(0).get("message").contains("connection reset"),
                install.getContexts().toString());
    }

    @Test
    void normalSentinelDrivenCompletionStillSucceeds() {
        Build build = new Build();
        build.setId("happy-build:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setPhases(new CopyOnWriteArrayList<>());

        CodeBuildRunner.PhaseSession session = runner().newPhaseSession(build, "lg", "ls", "us-east-1");
        for (String phase : List.of("INSTALL", "PRE_BUILD", "BUILD", "POST_BUILD")) {
            session.accept(("___FLOCI_PHASE_START___ " + phase + "\n").getBytes(StandardCharsets.UTF_8));
            session.accept(("___FLOCI_PHASE_END___ " + phase + " 0\n").getBytes(StandardCharsets.UTF_8));
        }

        boolean anyPhaseFailed = session.finish(0L, null);

        assertFalse(anyPhaseFailed);
        assertEquals(4, build.getPhases().size());
        assertTrue(build.getPhases().stream().allMatch(p -> "SUCCEEDED".equals(p.getPhaseStatus())));
    }

    // Regression coverage for the bounded exec-attach retry: runPhaseSession only retries a
    // dead attach stream with a fresh exec while PhaseSession.hasStarted() is still false. This
    // is the exact gate that decision is made on, so it must flip at the right moment: false
    // until the first phase-start sentinel is parsed, true forever after (even once that phase
    // has also ended) — retrying past that point could duplicate a real CFN/CDK operation.

    @Test
    void hasStartedIsFalseUntilFirstPhaseStartSentinel() {
        Build build = new Build();
        build.setId("gate-build:1");
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setPhases(new CopyOnWriteArrayList<>());

        CodeBuildRunner.PhaseSession session = runner().newPhaseSession(build, "lg", "ls", "us-east-1");

        assertFalse(session.hasStarted(), "no output received yet: retry must still be allowed");

        session.accept("___FLOCI_PHASE_START___ INSTALL\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(session.hasStarted(), "a phase has started: retry must no longer be allowed");

        session.accept("___FLOCI_PHASE_END___ INSTALL 0\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(session.hasStarted(), "must stay true once a phase has ended, not reset");
    }

    // The attach retry must back off between attempts, not burn all of them within
    // milliseconds. Observed live (issues/0030): three attempts logged 11 ms apart, all
    // drawing stale keep-alive connections from the same streaming-pool cohort that Podman
    // had already closed — so retrying instantly is guaranteed to re-fail. Each failed
    // attach discards its dead connection, so spaced attempts progressively drain the
    // stale cohort; instant attempts cannot.

    @Test
    void attachRetryBacksOffExponentiallyBetweenAttempts() {
        assertEquals(500L, CodeBuildRunner.attachRetryDelayMs(1));
        assertEquals(1_000L, CodeBuildRunner.attachRetryDelayMs(2));
        assertEquals(2_000L, CodeBuildRunner.attachRetryDelayMs(3));
        assertEquals(4_000L, CodeBuildRunner.attachRetryDelayMs(4));
    }

    @Test
    void attachRetryHasEnoughAttemptsToOutlastAStaleConnectionCohort() {
        // Three draws cannot drain the stale connections a ~15-container fan-out leaves in
        // the streaming pool; five spaced draws (7.5s total backoff) reliably can. Attempts
        // stay bounded because each retry is gated on PhaseSession.hasStarted() == false.
        assertEquals(5, CodeBuildRunner.PHASE_ATTACH_RETRY_MAX_ATTEMPTS);
    }
}
