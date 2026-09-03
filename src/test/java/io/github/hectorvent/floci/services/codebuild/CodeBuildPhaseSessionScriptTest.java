package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @TempDir
    Path tempDir;

    private record ShellRun(int exitCode, String output, List<String> sentinels) {}

    private ShellRun run(String shell, boolean bashMode, List<String> install, List<String> preBuild,
                         List<String> build, List<String> postBuild) throws Exception {
        return run(shell, bashMode, null, Map.of(), install, preBuild, build, postBuild);
    }

    private ShellRun run(String shell, boolean bashMode, String caCertPath, Map<String, String> extraEnv,
                         List<String> install, List<String> preBuild,
                         List<String> build, List<String> postBuild) throws Exception {
        String script = CodeBuildRunner.phaseSessionScript(install, preBuild, build, postBuild, bashMode, caCertPath);
        ProcessBuilder pb = new ProcessBuilder(shell, "-c", script).redirectErrorStream(true);
        // The build host may itself export CA bundle variables (e.g. a corporate proxy);
        // drop them so only the values a test sets explicitly reach the script.
        pb.environment().remove("NODE_EXTRA_CA_CERTS");
        pb.environment().remove("AWS_CA_BUNDLE");
        // buildEnvList injects this into the container environment before the session starts.
        pb.environment().put("CODEBUILD_BUILD_SUCCEEDING", "1");
        pb.environment().putAll(extraEnv);
        Process process = pb.start();
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

    @Test
    void postBuildSeesBuildSucceedingOneWhenAllPhasesPass() throws Exception {
        ShellRun run = runBash(
                List.of(),
                List.of(),
                List.of("echo build-work"),
                List.of("test \"$CODEBUILD_BUILD_SUCCEEDING\" -eq 1",
                        "if [ $CODEBUILD_BUILD_SUCCEEDING -eq 1 ]; then echo guarded-work; fi"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("guarded-work"), run.output());
    }

    @Test
    void postBuildSeesBuildSucceedingZeroAfterBuildFailure() throws Exception {
        ShellRun run = runBash(
                List.of(),
                List.of(),
                List.of("false"),
                List.of("if [ $CODEBUILD_BUILD_SUCCEEDING -eq 1 ]; then echo guarded-work; fi",
                        "test \"$CODEBUILD_BUILD_SUCCEEDING\" -eq 0",
                        "echo saw-failure"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 1"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertFalse(run.output().contains("guarded-work"), run.output());
        assertTrue(run.output().contains("saw-failure"), run.output());
    }

    @Test
    void postBuildSeesBuildSucceedingOneWhenAllPhasesPassInShFallback() throws Exception {
        ShellRun run = runSh(
                List.of(),
                List.of(),
                List.of("echo build-work"),
                List.of("test \"$CODEBUILD_BUILD_SUCCEEDING\" -eq 1", "echo guarded-work"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("guarded-work"), run.output());
    }

    @Test
    void postBuildSeesBuildSucceedingZeroAfterBuildFailureInShFallback() throws Exception {
        ShellRun run = runSh(
                List.of(),
                List.of(),
                List.of("false"),
                List.of("test \"$CODEBUILD_BUILD_SUCCEEDING\" -eq 0", "echo saw-failure"));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 1"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
        assertTrue(run.output().contains("saw-failure"), run.output());
    }

    // ── transparent AWS endpoints — combined CA bundle prelude ────────────────

    @Test
    void caBundleIsBuiltAndExportedBeforeAnyPhase() throws Exception {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "A POSIX shell is required for script tests");
        Path caCert = tempDir.resolve("floci-ca.pem");
        Files.writeString(caCert, "FLOCI-CA\n");

        ShellRun run = run("sh", false, caCert.toString(), Map.of(),
                List.of("echo \"install-bundle=$NODE_EXTRA_CA_CERTS\""),
                List.of("echo \"pre-bundle=$AWS_CA_BUNDLE\""),
                List.of(),
                List.of());

        Path bundle = tempDir.resolve("floci-ca.pem.bundle");
        assertTrue(run.output().contains("install-bundle=" + bundle), run.output());
        assertTrue(run.output().contains("pre-bundle=" + bundle), run.output());
        assertEquals("FLOCI-CA\n", Files.readString(bundle));
    }

    @Test
    void caBundlePreservesPreExistingNodeExtraCaCertsAndAwsCaBundle() throws Exception {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "A POSIX shell is required for script tests");
        Path caCert = tempDir.resolve("floci-ca.pem");
        Files.writeString(caCert, "FLOCI-CA\n");
        Path nodeCa = tempDir.resolve("node-extra.pem");
        Files.writeString(nodeCa, "NODE-USER-CA\n");
        Path awsCa = tempDir.resolve("aws-bundle.pem");
        Files.writeString(awsCa, "AWS-USER-CA\n");

        ShellRun run = run("sh", false, caCert.toString(),
                Map.of("NODE_EXTRA_CA_CERTS", nodeCa.toString(), "AWS_CA_BUNDLE", awsCa.toString()),
                List.of("cat \"$NODE_EXTRA_CA_CERTS\"", "test \"$NODE_EXTRA_CA_CERTS\" = \"$AWS_CA_BUNDLE\""),
                List.of(),
                List.of(),
                List.of());

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ INSTALL 0"), run.output());
        String bundleContent = Files.readString(tempDir.resolve("floci-ca.pem.bundle"));
        assertTrue(bundleContent.contains("FLOCI-CA"), bundleContent);
        assertTrue(bundleContent.contains("NODE-USER-CA"), bundleContent);
        assertTrue(bundleContent.contains("AWS-USER-CA"), bundleContent);
        assertTrue(bundleContent.indexOf("FLOCI-CA") < bundleContent.indexOf("NODE-USER-CA"),
                "Floci's certificate must seed the bundle: " + bundleContent);
    }

    @Test
    void caBundleExportsPersistAcrossEntriesAndPhasesInBashMode() throws Exception {
        Assumptions.assumeTrue(new File("/bin/bash").canExecute() || new File("/usr/bin/bash").canExecute(),
                "bash is required for driver script tests");
        Path caCert = tempDir.resolve("floci-ca.pem");
        Files.writeString(caCert, "FLOCI-CA\n");
        String bundle = caCert + ".bundle";

        ShellRun run = run("bash", true, caCert.toString(), Map.of(),
                List.of("test \"$NODE_EXTRA_CA_CERTS\" = \"" + bundle + "\""),
                List.of("test \"$AWS_CA_BUNDLE\" = \"" + bundle + "\""),
                List.of("grep -q FLOCI-CA \"$NODE_EXTRA_CA_CERTS\""),
                List.of("test \"$NODE_EXTRA_CA_CERTS\" = \"" + bundle + "\""));

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ INSTALL 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ PRE_BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ BUILD 0"), run.output());
        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ POST_BUILD 0"), run.output());
    }

    @Test
    void missingStagedCertificateLeavesCaEnvironmentUntouched() throws Exception {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "A POSIX shell is required for script tests");
        Path nodeCa = tempDir.resolve("node-extra.pem");
        Files.writeString(nodeCa, "NODE-USER-CA\n");

        ShellRun run = run("sh", false, tempDir.resolve("absent.pem").toString(),
                Map.of("NODE_EXTRA_CA_CERTS", nodeCa.toString()),
                List.of("test \"$NODE_EXTRA_CA_CERTS\" = \"" + nodeCa + "\"", "test -z \"${AWS_CA_BUNDLE:-}\""),
                List.of(),
                List.of(),
                List.of());

        assertTrue(run.sentinels().contains("___FLOCI_PHASE_END___ INSTALL 0"), run.output());
        assertFalse(Files.exists(tempDir.resolve("absent.pem.bundle")));
    }
}
