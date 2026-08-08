package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All buildspec phases run in one shell session, so unexported variables and the
 * working directory persist from install through post_build; sentinel lines carry
 * each phase's exit code (or SKIPPED) back to the runner.
 */
class CodeBuildPhaseSessionScriptTest {

    private record ShellRun(int exitCode, String output, List<String> sentinels) {}

    @BeforeEach
    void requireShell() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "A POSIX shell is required for script tests");
    }

    private ShellRun run(List<String> install, List<String> preBuild,
                         List<String> build, List<String> postBuild) throws Exception {
        String script = CodeBuildRunner.phaseSessionScript(install, preBuild, build, postBuild);
        Process process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        List<String> sentinels = output.lines()
                .filter(l -> l.startsWith("___FLOCI_PHASE_"))
                .toList();
        return new ShellRun(exitCode, output, sentinels);
    }

    @Test
    void unexportedVariableFromPreBuildIsVisibleInBuild() throws Exception {
        ShellRun run = run(
                List.of(),
                List.of("ENABLE_EXTERNAL_PIPELINE_ACCOUNT=\"no\""),
                List.of("if [ $ENABLE_EXTERNAL_PIPELINE_ACCOUNT = \"yes\" ]; then echo external; fi",
                        "test \"$ENABLE_EXTERNAL_PIPELINE_ACCOUNT\" = \"no\""),
                List.of());

        assertEquals(List.of(
                "___FLOCI_PHASE_START___ INSTALL",
                "___FLOCI_PHASE_END___ INSTALL 0",
                "___FLOCI_PHASE_START___ PRE_BUILD",
                "___FLOCI_PHASE_END___ PRE_BUILD 0",
                "___FLOCI_PHASE_START___ BUILD",
                "___FLOCI_PHASE_END___ BUILD 0",
                "___FLOCI_PHASE_START___ POST_BUILD",
                "___FLOCI_PHASE_END___ POST_BUILD 0"), run.sentinels());
    }

    @Test
    void workingDirectoryChangePersistsIntoNextPhase() throws Exception {
        ShellRun run = run(
                List.of("cd /usr"),
                List.of("test \"$(pwd)\" = \"/usr\""),
                List.of("test \"$(pwd)\" = \"/usr\""),
                List.of());

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 0"), run.output());
    }

    @Test
    void failingBuildStopsAtFirstFailureAndStillRunsPostBuild() throws Exception {
        ShellRun run = run(
                List.of(),
                List.of("POST_MARKER=\"kept\""),
                List.of("echo before-failure", "false", "echo never-reached"),
                List.of("test \"$POST_MARKER\" = \"kept\"", "echo post-build-ran"));

        assertTrue(run.output().contains("before-failure"), run.output());
        assertFalse(run.output().contains("never-reached"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 1"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("post-build-ran"), run.output());
    }

    @Test
    void failingInstallSkipsPreBuildAndBuildButRunsPostBuild() throws Exception {
        ShellRun run = run(
                List.of("INSTALL_MARKER=\"set\"", "false"),
                List.of("echo marker-pre-build"),
                List.of("echo marker-build"),
                List.of("test \"$INSTALL_MARKER\" = \"set\"", "echo marker-post-build"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ INSTALL 1"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD SKIPPED"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD SKIPPED"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertFalse(run.output().contains("marker-pre-build"), run.output());
        assertFalse(run.output().lines().anyMatch(l -> l.equals("marker-build")), run.output());
        assertTrue(run.output().contains("marker-post-build"), run.output());
    }
}
