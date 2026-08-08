package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No failure class may leave a Build IN_PROGRESS forever: CodePipeline actions poll
 * buildComplete, so a build thread killed by an Error (e.g. OutOfMemoryError) must
 * still end the build as FAILED with an error context.
 */
class CodeBuildRunnerFailureHandlingTest {

    private CodeBuildRunner runner() {
        return new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null);
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
}
