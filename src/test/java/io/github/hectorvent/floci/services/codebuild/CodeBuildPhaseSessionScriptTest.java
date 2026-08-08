package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Assumptions;
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
 * each phase's exit code (or SKIPPED) back to the runner. In the primary bash mode
 * each command entry additionally runs in its own child shell with a state snapshot
 * restored in between, so shell options (set -e/-u/-x) and exit never leak from one
 * entry into the next — matching the real CodeBuild agent.
 */
class CodeBuildPhaseSessionScriptTest {

    private record ShellRun(int exitCode, String output, List<String> sentinels) {}

    private ShellRun run(String shell, boolean bashMode, List<String> install, List<String> preBuild,
                         List<String> build, List<String> postBuild) throws Exception {
        String script = CodeBuildRunner.phaseSessionScript(install, preBuild, build, postBuild, bashMode);
        Process process = new ProcessBuilder(shell, "-c", script).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        List<String> sentinels = output.lines()
                .filter(l -> l.startsWith("___FLOCI_PHASE_"))
                .toList();
        return new ShellRun(exitCode, output, sentinels);
    }

    private ShellRun runSh(List<String> install, List<String> preBuild,
                           List<String> build, List<String> postBuild) throws Exception {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "A POSIX shell is required for script tests");
        return run("sh", false, install, preBuild, build, postBuild);
    }

    private ShellRun runBash(List<String> install, List<String> preBuild,
                             List<String> build, List<String> postBuild) throws Exception {
        Assumptions.assumeTrue(new File("/bin/bash").canExecute() || new File("/usr/bin/bash").canExecute(),
                "bash is required for driver script tests");
        return run("bash", true, install, preBuild, build, postBuild);
    }

    @Test
    void unexportedVariableFromPreBuildIsVisibleInBuild() throws Exception {
        ShellRun run = runSh(
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
        ShellRun run = runSh(
                List.of("cd /usr"),
                List.of("test \"$(pwd)\" = \"/usr\""),
                List.of("test \"$(pwd)\" = \"/usr\""),
                List.of());

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 0"), run.output());
    }

    @Test
    void failingBuildStopsAtFirstFailureAndStillRunsPostBuild() throws Exception {
        ShellRun run = runSh(
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
        ShellRun run = runSh(
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

    @Test
    void setOptionsDoNotLeakAcrossEntries() throws Exception {
        ShellRun run = runBash(
                List.of(),
                List.of("set -e && BOOTSTRAP=\"done\""),
                List.of("""
                        false 2> /dev/null
                        status=$?
                        if [ $status -ne 0 ]; then MIGRATION="no"; else MIGRATION="yes"; fi""",
                        "test \"$MIGRATION\" = \"no\"",
                        "test \"$BOOTSTRAP\" = \"done\""),
                List.of("echo marker-post-build"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("marker-post-build"), run.output());
    }

    @Test
    void setEFailureKillsOnlyItsEntryAndPostBuildStillRuns() throws Exception {
        ShellRun run = runBash(
                List.of(),
                List.of("PIPELINE_STAGE=\"deploy\""),
                List.of("echo before-failure", "set -e && false", "echo never-reached"),
                List.of("test \"$PIPELINE_STAGE\" = \"deploy\"", "echo marker-post-build"));

        assertTrue(run.output().contains("before-failure"), run.output());
        assertFalse(run.output().contains("never-reached"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 1"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("marker-post-build"), run.output());
    }

    @Test
    void exitInsideEntryFailsPhaseButSessionSurvives() throws Exception {
        ShellRun run = runBash(
                List.of(),
                List.of(),
                List.of("exit 7", "echo never-reached"),
                List.of("echo marker-post-build"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 7"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertFalse(run.output().contains("never-reached"), run.output());
        assertTrue(run.output().contains("marker-post-build"), run.output());
    }

    @Test
    void unexportedVariablesAndCwdPersistAcrossEntriesAndPhasesInBashMode() throws Exception {
        ShellRun run = runBash(
                List.of("cd /usr"),
                List.of("V=\"a\"", "V=\"$V-b\"", "cd bin"),
                List.of("test \"$V\" = \"a-b\"", "test \"$(pwd)\" = \"/usr/bin\""),
                List.of("test \"$(pwd)\" = \"/usr/bin\""));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
    }

    @Test
    void failingInstallSkipsPreBuildAndBuildButRunsPostBuildInBashMode() throws Exception {
        ShellRun run = runBash(
                List.of("INSTALL_MARKER=\"set\"", "set -e && false"),
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
