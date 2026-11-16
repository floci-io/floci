package io.floci.aws.services.glue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.MutableClock;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerGlueScriptRunnerTest {

    private static final String ENDPOINT = "http://floci:4566";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";
    private static final String SCRIPT = "s3://glue-demo/scripts/job.py";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final S3Service s3 = mock(S3Service.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final LambdaExecutionRoleCredentials roleCredentials = mock(LambdaExecutionRoleCredentials.class);
    private final GlueScriptRunner.Progress progress = mock(GlueScriptRunner.Progress.class);
    private final ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    private final MutableClock clock = new MutableClock();

    @Test
    void putsTheScriptAndItsArgumentsAfterFlocisSparkSettings() {
        List<String> command = DockerGlueScriptRunner.commandLine("/home/hadoop/workspace/job.py",
                ENDPOINT, List.of("--bucket", "glue-demo", "--JOB_NAME", "etl"));

        int script = command.indexOf("/home/hadoop/workspace/job.py");
        assertEquals(List.of("--bucket", "glue-demo", "--JOB_NAME", "etl"), command.subList(script + 1, command.size()));
        assertTrue(command.contains("spark.hadoop.fs.s3a.endpoint=http://floci:4566"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.path.style.access=true"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.connection.ssl.enabled=false"));
        assertTrue(command.contains("spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem"));
    }

    @Test
    void enablesSslForAnHttpsEndpoint() {
        List<String> command = DockerGlueScriptRunner.commandLine("/job.py", "https://floci:4566", List.of());

        assertTrue(command.contains("spark.hadoop.fs.s3a.connection.ssl.enabled=true"));
    }

    @Test
    void keepsAPythonScriptsNameAndNamesAnyOtherScript() {
        assertEquals("job.py", DockerGlueScriptRunner.scriptName(S3Uri.parse("s3://b/scripts/job.py")));
        assertEquals("script.py", DockerGlueScriptRunner.scriptName(S3Uri.parse("s3://b/scripts/job")));
        assertEquals("script.py", DockerGlueScriptRunner.scriptName(S3Uri.parse("s3://b/job.sh")));
    }

    @Test
    void turnsArgumentsIntoNameValuePairsInOrder() {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("--a", "1");
        arguments.put("--flag", null);

        assertEquals(List.of("--a", "1", "--flag", ""), GlueScriptRunner.argumentList(arguments));
    }

    @Test
    void anExitOfZeroEndsTheRunAndTheContainerIsRemoved() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("script")).thenReturn(copy);
        Map<String, String> copied = copiedIn(copy);
        inS3("glue-demo", "scripts/job.py", "print('hi')");

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertEquals(0, outcome.exitCode());
        assertFalse(outcome.stopped());
        assertFalse(outcome.timedOut());
        // Extracted at the container's root, where the Glue image keeps its workspace.
        assertEquals(Map.of("home/hadoop/workspace/job.py", "print('hi')"), copied);
        verify(spec).withEntrypoint(List.of("spark-submit"));
        verify(spec).withCmd(argThat((List<String> cmd) -> cmd.contains("/home/hadoop/workspace/job.py")
                && cmd.getLast().equals("jr_1") && cmd.contains("--JOB_NAME")));
        verify(lifecycle).startCreated(eq("script"), any());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void aNonZeroExitIsTheScriptsExitCode() {
        DockerGlueScriptRunner runner = runner(container("script", false, 3L));
        inS3("glue-demo", "scripts/job.py", "raise SystemExit(3)");

        assertEquals(3, runner.run(request(Duration.ZERO), progress).exitCode());
    }

    @Test
    void theJobRolesCredentialsReachTheScriptAndAreGivenBackWhenItEnds() {
        SessionCreds session = new SessionCreds("ASIAGLUE", "secret", "token");
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        when(roleCredentials.forRole(ROLE, "000000000000", "jr_1")).thenReturn(Optional.of(session));
        inS3("glue-demo", "scripts/job.py", "pass");

        runner.run(request(Duration.ZERO), progress);

        verify(awsEnv).sdkBaselineEnv("us-east-1", Optional.empty(), Optional.of(session), "000000000000");
        verify(roleCredentials).unregister("000000000000", "ASIAGLUE");
    }

    @Test
    void aScriptThatCannotBeReadFailsTheRunBeforeAnyContainerExists() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        when(s3.headObject("glue-demo", "scripts/job.py"))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertTrue(outcome.failure().contains("Could not read the script " + SCRIPT));
        verify(lifecycle, never()).create(any());
    }

    @Test
    void aRunCancelledBeforeItStartsNeverCreatesAContainer() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        when(progress.cancelled()).thenReturn(true);

        assertTrue(runner.run(request(Duration.ZERO), progress).stopped());
        verify(lifecycle, never()).create(any());
    }

    @Test
    void aStopWhileTheImageIsPulledNeverStartsTheScript() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        inS3("glue-demo", "scripts/job.py", "pass");
        when(lifecycle.create(any())).thenAnswer(pulling -> {
            runner.stop("jr_1");
            return "script";
        });

        assertTrue(runner.run(request(Duration.ZERO), progress).stopped());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void aStopWhileTheScriptRunsEndsTheRunAndRemovesTheContainer() {
        DockerGlueScriptRunner runner = runner(container("script", true, null));
        inS3("glue-demo", "scripts/job.py", "while True: pass");
        when(docker.inspectContainerCmd("script").exec().getState().getRunning()).thenAnswer(look -> {
            runner.stop("jr_1");
            return true;
        });

        assertTrue(runner.run(request(Duration.ZERO), progress).stopped());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void aScriptPastItsTimeoutEndsTheRunAndRemovesTheContainer() {
        DockerGlueScriptRunner runner = runner(container("script", true, null));
        inS3("glue-demo", "scripts/job.py", "while True: pass");
        when(docker.inspectContainerCmd("script").exec().getState().getRunning()).thenAnswer(look -> {
            clock.advance(Duration.ofMinutes(31));
            return true;
        });

        assertTrue(runner.run(request(Duration.ofMinutes(30)), progress).timedOut());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void aRunWithoutATimeoutIsNeverTimedOut() {
        DockerGlueScriptRunner runner = runner(container("script", true, null));
        inS3("glue-demo", "scripts/job.py", "while True: pass");
        AtomicInteger looks = new AtomicInteger();
        when(docker.inspectContainerCmd("script").exec().getState().getRunning()).thenAnswer(look -> {
            clock.advance(Duration.ofDays(30));
            // Ends on the second look, as the container exits.
            return looks.incrementAndGet() < 2;
        });
        when(docker.inspectContainerCmd("script").exec().getState().getExitCodeLong()).thenReturn(0L);

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertFalse(outcome.timedOut());
        assertNull(outcome.failure());
    }

    @Test
    void theTimeoutCountsFromTheRunsStartSoAnImagePullIsPartOfIt() {
        DockerGlueScriptRunner runner = runner(container("script", true, null));
        inS3("glue-demo", "scripts/job.py", "pass");
        when(lifecycle.create(any())).thenAnswer(pulling -> {
            clock.advance(Duration.ofMinutes(31));
            return "script";
        });

        assertTrue(runner.run(request(Duration.ofMinutes(30)), progress).timedOut());
    }

    @Test
    void aSingleRefusedLookAtTheContainerDoesNotEndTheRun() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        inS3("glue-demo", "scripts/job.py", "pass");
        AtomicInteger looks = new AtomicInteger();
        when(docker.inspectContainerCmd("script")).thenAnswer(look -> {
            if (looks.incrementAndGet() == 1) {
                throw new IllegalStateException("daemon busy");
            }
            return mock(InspectContainerCmd.class, RETURNS_DEEP_STUBS);
        });

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertNull(outcome.failure());
        assertTrue(looks.get() >= 2);
    }

    @Test
    void containerLooksThatKeepFailingFailTheRunAndRemoveTheContainer() {
        DockerGlueScriptRunner runner = runner(container("script", true, null));
        inS3("glue-demo", "scripts/job.py", "pass");
        when(docker.inspectContainerCmd("script")).thenThrow(new IllegalStateException("daemon gone"));

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertEquals("daemon gone", outcome.failure());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void aContainerThatCannotBeCreatedFailsTheRunAndStillReleasesItsCredentials() {
        SessionCreds session = new SessionCreds("ASIAGLUE", "secret", "token");
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        when(roleCredentials.forRole(ROLE, "000000000000", "jr_1")).thenReturn(Optional.of(session));
        inS3("glue-demo", "scripts/job.py", "pass");
        when(lifecycle.create(any())).thenThrow(new IllegalStateException("no space left on device"));

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertEquals("no space left on device", outcome.failure());
        verify(roleCredentials).unregister("000000000000", "ASIAGLUE");
    }

    private GlueScriptRunner.Run request(Duration timeout) {
        return new GlueScriptRunner.Run("jr_1", "etl", "000000000000", "us-east-1", ROLE, SCRIPT,
                List.of("--JOB_NAME", "etl", "--JOB_RUN_ID", "jr_1"), "glue-image:5", null, timeout);
    }

    // A runner whose script container is "id", running or not, with an exit code once it is not.
    private DockerGlueScriptRunner runner(String id) {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveImage(anyString())).thenAnswer(image -> image.getArgument(0));
        when(builder.resolveDockerNetwork(any())).thenReturn(Optional.empty());
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn(id);
        when(lifecycle.resolveImageForLaunch(anyString(), eq(ImagePullBehavior.DEFAULT), isNull()))
                .thenAnswer(pull -> new LaunchImage(pull.getArgument(0), null));
        when(awsEnv.flociEndpoint()).thenReturn(ENDPOINT);
        when(roleCredentials.forRole(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        return new DockerGlueScriptRunner(builder, lifecycle, logStreamer, awsEnv, roleCredentials, s3, config, clock,
                Duration.ZERO);
    }

    private String container(String id, boolean running, Long exitCode) {
        when(docker.inspectContainerCmd(id).exec().getState().getRunning()).thenReturn(running);
        when(docker.inspectContainerCmd(id).exec().getState().getExitCodeLong()).thenReturn(exitCode);
        return id;
    }

    // What each tar copied into the container holds, by entry, read as the daemon reads it.
    private static Map<String, String> copiedIn(CopyArchiveToContainerCmd copy) {
        Map<String, String> copied = new LinkedHashMap<>();
        when(copy.withTarInputStream(any())).thenAnswer(streaming -> {
            InputStream stream = streaming.getArgument(0);
            TarArchiveInputStream tar = new TarArchiveInputStream(stream);
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                copied.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
            stream.transferTo(OutputStream.nullOutputStream());
            return copy;
        });
        return copied;
    }

    // An object in Floci's S3, which each read opens afresh.
    private void inS3(String bucket, String key, String content) {
        S3Object object = new S3Object(bucket, key, content.getBytes(StandardCharsets.UTF_8),
                "application/octet-stream");
        when(s3.openObject(bucket, key, null))
                .thenAnswer(read -> new S3Service.ObjectRead(object, new ByteArrayInputStream(object.getData())));
    }
}
