package io.floci.aws.services.glue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
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
                ENDPOINT, "us-east-1", "000000000000", List.of("--bucket", "glue-demo", "--JOB_NAME", "etl"));

        int script = command.indexOf("/home/hadoop/workspace/job.py");
        assertEquals(List.of("--bucket", "glue-demo", "--JOB_NAME", "etl"), command.subList(script + 1, command.size()));
        assertTrue(command.contains("spark.hadoop.fs.s3a.endpoint=http://floci:4566"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.endpoint.region=us-east-1"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.path.style.access=true"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.connection.ssl.enabled=false"));
        assertTrue(command.contains("spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled=true"));
        assertTrue(command.contains("spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem"));
        assertTrue(command.contains("spark.hadoop.aws.glue.endpoint=http://floci:4566"));
        assertTrue(command.contains("spark.hadoop.hive.metastore.glue.catalogid=000000000000"));
        assertTrue(command.contains("spark.hadoop.aws.region=us-east-1"));
    }

    @Test
    void enablesSslForAnHttpsEndpoint() {
        List<String> command = DockerGlueScriptRunner.commandLine("/job.py", "https://floci:4566",
                "us-east-1", "000000000000", List.of());

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

    @Test
    void timeoutCountsFromRecordedStartedOnEvenBeforeWorkerBegins() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        Instant startedOn = clock.instant();
        clock.advance(Duration.ofMinutes(31));

        GlueScriptRunner.Outcome outcome = runner.run(request(startedOn, Duration.ofMinutes(30)), progress);

        assertTrue(outcome.timedOut());
        verify(lifecycle, never()).create(any());
    }

    @Test
    void timeoutDuringPreparationDoesNotStartTheContainer() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        inS3("glue-demo", "scripts/job.py", "pass");
        when(lifecycle.create(any())).thenAnswer(pulling -> {
            clock.advance(Duration.ofMinutes(31));
            return "script";
        });

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ofMinutes(30)), progress);

        assertTrue(outcome.timedOut());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void timeoutAtInspectionReturnsTimedOutEvenIfContainerAlsoStopped() {
        DockerGlueScriptRunner runner = runner(container("script", true, 0L));
        inS3("glue-demo", "scripts/job.py", "pass");
        InspectContainerResponse response = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(response.getState().getRunning()).thenReturn(false);
        when(response.getState().getExitCodeLong()).thenReturn(0L);
        when(docker.inspectContainerCmd("script").exec()).thenAnswer(look -> {
            clock.advance(Duration.ofMinutes(31));
            return response;
        });

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ofMinutes(30)), progress);

        assertTrue(outcome.timedOut());
    }

    @Test
    void resolvesRoleNameViaIamServiceForScriptCredentials() {
        IamService iamService = mock(IamService.class);
        AssumeRolePolicyEvaluator trust = mock(AssumeRolePolicyEvaluator.class);
        String resolvedArn = "arn:aws:iam::000000000000:role/service-role/glue-role";
        IamRole role = new IamRole("role-id", "glue-role", "/service-role/", resolvedArn, "{}");
        when(iamService.findRole("000000000000", "glue-role")).thenReturn(Optional.of(role));
        SessionCreds session = new SessionCreds("ASIAGLUE", "secret", "token");
        when(roleCredentials.forRole(resolvedArn, "000000000000", "jr_1")).thenReturn(Optional.of(session));
        DockerGlueScriptRunner runner = runner("script", iamService, trust);
        container("script", false, 0L);
        inS3("glue-demo", "scripts/job.py", "pass");

        GlueScriptRunner.Outcome outcome = runner.run(request("glue-role", clock.instant(), Duration.ZERO), progress);

        assertEquals(0, outcome.exitCode());
        verify(awsEnv).sdkBaselineEnv("us-east-1", Optional.empty(), Optional.of(session), "000000000000");
        verify(roleCredentials).unregister("000000000000", "ASIAGLUE");
    }

    @Test
    void rejectsCrossAccountRoleArnBeforeCreatingContainer() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));

        GlueScriptRunner.Outcome outcome = runner.run(
                request("arn:aws:iam::999999999999:role/admin", clock.instant(), Duration.ZERO), progress);

        assertTrue(outcome.failure().contains("Cross-account"));
        verify(lifecycle, never()).create(any());
    }

    @Test
    void enforcementRejectsRoleThatDoesNotTrustGlue() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        IamService iamService = mock(IamService.class);
        AssumeRolePolicyEvaluator trust = mock(AssumeRolePolicyEvaluator.class);
        IamRole role = new IamRole("role-id", "glue", "/", ROLE, "{}");
        when(iamService.findRole("000000000000", "glue")).thenReturn(Optional.of(role));
        when(trust.allowsService("{}", "glue.amazonaws.com",
                "arn:aws:glue:us-east-1:000000000000:job/etl", "000000000000")).thenReturn(false);
        DockerGlueScriptRunner runner = runner("script", iamService, trust);
        container("script", false, 0L);

        GlueScriptRunner.Outcome outcome = runner.run(request(ROLE, clock.instant(), Duration.ZERO), progress);

        assertTrue(outcome.failure().contains("does not trust"));
        verify(lifecycle, never()).create(any());
    }

    @Test
    void enforcementAllowsRoleThatTrustsGlue() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        IamService iamService = mock(IamService.class);
        AssumeRolePolicyEvaluator trust = mock(AssumeRolePolicyEvaluator.class);
        IamRole role = new IamRole("role-id", "glue", "/", ROLE, "{\"Statement\":[]}");
        when(iamService.findRole("000000000000", "glue")).thenReturn(Optional.of(role));
        when(trust.allowsService("{\"Statement\":[]}", "glue.amazonaws.com",
                "arn:aws:glue:us-east-1:000000000000:job/etl", "000000000000")).thenReturn(true);
        SessionCreds session = new SessionCreds("ASIAGLUE", "secret", "token");
        when(roleCredentials.forRole(ROLE, "000000000000", "jr_1")).thenReturn(Optional.of(session));
        DockerGlueScriptRunner runner = runner("script", iamService, trust);
        container("script", false, 0L);
        inS3("glue-demo", "scripts/job.py", "pass");

        GlueScriptRunner.Outcome outcome = runner.run(request(ROLE, clock.instant(), Duration.ZERO), progress);

        assertEquals(0, outcome.exitCode());
        verify(roleCredentials).unregister("000000000000", "ASIAGLUE");
    }

    @Test
    void stopDuringScriptUploadAbortsStreamAndRemovesContainer() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("script")).thenReturn(copy);
        when(copy.withTarInputStream(any())).thenAnswer(streaming -> {
            InputStream stream = streaming.getArgument(0);
            try {
                stream.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // The stopped writer closes the pipe once it sees the stop.
            }
            return copy;
        });
        byte[] content = "print('x')\n".getBytes(StandardCharsets.UTF_8);
        S3Object object = new S3Object("glue-demo", "scripts/job.py", content, "application/octet-stream");
        InputStream stoppingStream = new InputStream() {
            @Override
            public int read() {
                runner.stop("jr_1");
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                runner.stop("jr_1");
                b[off] = 'x';
                return 1;
            }
        };
        when(s3.openObject("glue-demo", "scripts/job.py", null))
                .thenReturn(new S3Service.ObjectRead(object, stoppingStream));

        GlueScriptRunner.Outcome outcome = runner.run(request(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("script", null, 0);
    }

    @Test
    void removeLeftoverContainersContinuesWhenOneContainerRemovalFails() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        String owner = ContainerStorageHelper.ownerIdentity(config);
        Map<String, String> labels = Map.of(
                ContainerStorageHelper.SERVICE_LABEL, DockerGlueScriptRunner.SERVICE,
                ContainerStorageHelper.OWNER_LABEL, owner);
        Container first = mock(Container.class);
        when(first.getId()).thenReturn("c1");
        when(first.getLabels()).thenReturn(labels);
        Container second = mock(Container.class);
        when(second.getId()).thenReturn("c2");
        when(second.getLabels()).thenReturn(labels);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("daemon busy")).when(lifecycle).removeIfExistsStrict("c1", true);

        runner.removeLeftoverContainers();

        verify(lifecycle).removeIfExistsStrict("c1", true);
        verify(lifecycle).removeIfExistsStrict("c2", true);
    }

    @Test
    void isAvailableReflectsDockerDaemonReachability() {
        DockerGlueScriptRunner runner = runner(container("script", false, 0L));
        assertTrue(runner.isAvailable());

        when(docker.pingCmd().exec()).thenThrow(new IllegalStateException("daemon unreachable"));
        assertFalse(runner.isAvailable());
    }

    private GlueScriptRunner.Run request(Duration timeout) {
        return request(clock.instant(), timeout);
    }

    private GlueScriptRunner.Run request(Instant startedOn, Duration timeout) {
        return request(ROLE, startedOn, timeout);
    }

    private GlueScriptRunner.Run request(String role, Instant startedOn, Duration timeout) {
        return new GlueScriptRunner.Run("jr_1", "etl", "000000000000", "us-east-1", role, SCRIPT,
                List.of("--JOB_NAME", "etl", "--JOB_RUN_ID", "jr_1"), "glue-image:5", null, startedOn, timeout);
    }

    // A runner whose script container is "id", running or not, with an exit code once it is not.
    private DockerGlueScriptRunner runner(String id) {
        return runner(id, null, null);
    }

    private DockerGlueScriptRunner runner(String id, IamService iamService, AssumeRolePolicyEvaluator trust) {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveImage(anyString())).thenAnswer(image -> image.getArgument(0));
        when(builder.resolveDockerNetwork(any())).thenReturn(Optional.empty());
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn(id);
        when(lifecycle.resolveImageForLaunch(anyString(), eq(ImagePullBehavior.DEFAULT), isNull()))
                .thenAnswer(pull -> new LaunchImage(pull.getArgument(0), null));
        when(awsEnv.flociEndpoint()).thenReturn(ENDPOINT);
        return new DockerGlueScriptRunner(builder, lifecycle, logStreamer, awsEnv, roleCredentials, iamService, trust,
                s3, config, clock, Duration.ZERO);
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
