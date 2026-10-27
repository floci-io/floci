package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmrServerlessSparkRunnerTest {

    private static final String ENDPOINT = "http://floci:4566";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final SparkJobRunner.Progress progress = mock(SparkJobRunner.Progress.class);
    private final S3Service s3 = mock(S3Service.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);

    @Test
    void sparkSubmitRunsLocallyOnFlocisS3ThenTheRunsOwnSettings() {
        List<String> application = List.of("--conf", "spark.executor.cores=2", "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("spark.executor.cores", "4");
        defaults.put("spark.sql.shuffle.partitions", "8");

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(application, defaults), ENDPOINT);

        assertEquals(List.of("--master", "local[*]",
                "--conf", "spark.hadoop.fs.s3a.endpoint=http://floci:4566",
                "--conf", "spark.hadoop.fs.s3a.endpoint.region=eu-west-1",
                "--conf", "spark.hadoop.fs.s3a.path.style.access=true",
                "--conf", "spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled=true",
                "--conf", "spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
                "--conf", "spark.hadoop.fs.AbstractFileSystem.s3.impl=org.apache.hadoop.fs.s3a.S3A",
                "--conf", "spark.hadoop.aws.glue.endpoint=http://floci:4566",
                "--conf", "spark.hadoop.aws.region=eu-west-1",
                "--conf", "spark.executor.cores=4",
                "--conf", "spark.sql.shuffle.partitions=8",
                "--conf", "spark.executor.cores=2",
                "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/"), arguments);
    }

    @Test
    void anApplicationWithoutOptionsOrArgumentsFollowsFlocisSettings() {
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(
                job(List.of("s3://bucket/jobs/app.jar"), Map.of()), ENDPOINT);

        assertEquals("--conf", arguments.get(arguments.size() - 3));
        assertEquals("spark.hadoop.aws.region=eu-west-1", arguments.get(arguments.size() - 2));
        assertEquals("s3://bucket/jobs/app.jar", arguments.get(arguments.size() - 1));
    }

    @Test
    void sparkSubmitParametersSplitAsAShellWould() {
        assertEquals(List.of("--conf", "spark.driver.extraJavaOptions=-Da=1 -Db=2", "--conf", "x=it's", "a b", ""),
                EmrServerlessSparkRunner.splitParameters(
                        "  --conf \"spark.driver.extraJavaOptions=-Da=1 -Db=2\"\t--conf 'x=it'\\''s' a\\ b ''  "));
        // Inside double quotes a backslash escapes only ", \, $, ` and a newline, and is kept before anything else.
        assertEquals(List.of("--conf", "spark.driver.extraJavaOptions=-Dpattern=\\d+ -Dq=\"x\" -Dp=\\ $HOME", "onetwo"),
                EmrServerlessSparkRunner.splitParameters(
                        "--conf \"spark.driver.extraJavaOptions=-Dpattern=\\d+ -Dq=\\\"x\\\" -Dp=\\\\ \\$HOME\" one\\\ntwo"));
        assertEquals(List.of(), EmrServerlessSparkRunner.splitParameters(null));
        assertEquals(List.of(), EmrServerlessSparkRunner.splitParameters("   "));
    }

    @Test
    void theImageIsTheReleasesPublicImageUnlessATemplateNamesAnother() {
        assertEquals("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                EmrServerlessSparkRunner.image(null, "emr-7.5.0"));
        assertEquals("public.ecr.aws/emr-serverless/spark4/emr-spark-8.1.0:latest",
                EmrServerlessSparkRunner.image(null, "emr-spark-8.1.0"));
        assertEquals("registry.internal/emr-serverless/spark/emr-7.5.0:20250425",
                EmrServerlessSparkRunner.image("registry.internal/emr-serverless/spark/%s:20250425", "emr-7.5.0"));
    }

    @Test
    void theDriverRunsInTheJobsImageAndNetworkNamedAndLabelledForItsService() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(job(Duration.ZERO), progress);

        verify(builder).newContainer("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest");
        verify(spec).withName(ContainerStorageHelper.dockerName(config, "emr-serverless-00f0000000000001"));
        verify(spec).withDockerNetwork(Optional.of("floci-network"));
        verify(spec).withLabels(argThat(labels ->
                "emr-serverless".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))));
    }

    @Test
    void aTemplateThatNamesNoImageFailsTheRunWithItsReason() {
        EmrServerlessSparkRunner runner = runner();

        SparkJobRunner.Outcome outcome = runner.run(
                job("registry.internal/emr-serverless/spark/%d:latest", List.of("s3://bucket/jobs/app.jar"), Map.of(),
                        Duration.ZERO), progress);

        assertNull(outcome.exitCode());
        assertNotNull(outcome.failure());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aCancelEndsTheRunAndRemovesItsDriverEvenWhenDockerWouldRefuseAStop() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ofMillis(50));
        when(docker.stopContainerCmd("driver").withTimeout(0).exec())
                .thenThrow(new InternalServerErrorException("daemon busy"));
        // The driver never exits by itself, as a STREAMING run's; the cancel comes from another task,
        // whose exceptions go nowhere, while the driver is being watched.
        AtomicBoolean cancelled = new AtomicBoolean();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            if (cancelled.compareAndSet(false, true)) {
                try {
                    runner.stop(job.runId());
                } catch (RuntimeException ignored) {
                    // Dropped, as the executor drops what its task throws; the assertions below catch a lost cancel.
                }
            }
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).timedOut();
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aResetWhileTheImageIsPulledStopsTheRunOnceItsContainerIsUp() {
        EmrServerlessSparkRunner runner = runner();
        when(lifecycle.createAndStart(any())).thenAnswer(pulling -> {
            runner.stopManagedContainers();
            return new ContainerLifecycleManager.ContainerInfo("driver", Map.of());
        });

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).started();
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunCancelledWhileItsImageIsPulledNeverStartsItsDriver() {
        EmrServerlessSparkRunner runner = runner();
        // Not cancelled when the run begins, cancelled by the time its container is up.
        when(progress.cancelled()).thenReturn(false, true);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).started();
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunningDriverWhoseRunIsGoneIsStoppedAtTheNextLook() {
        EmrServerlessSparkRunner runner = runner();
        // A reset removes the run while its driver runs: not cancelled before the driver starts, nor at the
        // first look at it, then gone. Left to itself, the driver would run on for four more looks, then exit.
        when(progress.cancelled()).thenReturn(false, false, false, true);
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning())
                .thenReturn(true, true, true, true, true, false);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aStopAfterTheRunHasEndedIsDropped() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ZERO);
        assertEquals(0, runner.run(job, progress).exitCode());

        runner.stop(job.runId());

        // A stop kept past its run would halt the next run of this id before it started.
        assertEquals(0, runner.run(job, progress).exitCode());
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void aRunPastItsTimeoutIsToldSoBeforeItsDriverIsRemoved() {
        EmrServerlessSparkRunner runner = runner();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(true);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ofMillis(5)), progress);

        assertTrue(outcome.timedOut());
        InOrder order = inOrder(progress, lifecycle);
        order.verify(progress).timedOut();
        order.verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void theDriverIsWatchedWithoutHoldingADockerConnection() {
        EmrServerlessSparkRunner runner = runner();

        runner.run(job(Duration.ZERO), progress);

        // A look at the container is one short call; no wait or other stream is held while the driver runs.
        verify(docker, atLeastOnce()).inspectContainerCmd("driver");
        verify(docker, never()).waitContainerCmd(anyString());
    }

    @Test
    void theDriversOutputGoesGzippedToTheRunsLogFolderOnceItEnds() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDERR, bytes("INFO SparkContext: Running Spark version 3.5.2\n")),
                new Frame(StreamType.STDOUT, bytes("Pi is roughly 3.14\n")),
                new Frame(StreamType.STDOUT, bytes("done\n")));

        SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

        assertEquals(0, outcome.exitCode());
        assertEquals("Pi is roughly 3.14\ndone\n", written("logs", "emr/j-1/steps/s-1/stdout.gz"));
        assertEquals("INFO SparkContext: Running Spark version 3.5.2\n",
                written("logs", "emr/j-1/steps/s-1/stderr.gz"));
    }

    @Test
    void aLogFolderInAHadoopSchemeIsInTheSameBucket() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDOUT, bytes("done\n")));

        runner.run(job("s3n://logs/emr/j-1/steps/s-1/"), progress);

        assertEquals("done\n", written("logs", "emr/j-1/steps/s-1/stdout.gz"));
    }

    @Test
    void aCancelledRunsDriverIsStoppedBeforeItsLogsAreReadAndRemovedAfterThey() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job("s3://logs/emr/j-1/steps/s-1/");
        driverWrote(new Frame(StreamType.STDOUT, bytes("sleeping\n")));
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            runner.stop(job.runId());
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        // Stopped first, the driver changes nothing while its output is read, and all of that output is there.
        assertTrue(outcome.stopped());
        InOrder order = inOrder(lifecycle, docker, s3);
        order.verify(lifecycle).stop("driver", 0);
        order.verify(docker).logContainerCmd("driver");
        order.verify(s3).putObject(eq("logs"), eq("emr/j-1/steps/s-1/stdout.gz"), any(), anyString(), anyMap());
        order.verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void anInterruptedLogReadEndsDockersStreamAndWritesNothing() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        Closeable stream = mock(Closeable.class);
        // Floci shuts down while the output is read: Docker has started sending it, and never finishes.
        when(docker.logContainerCmd("driver").withStdOut(true).withStdErr(true).withFollowStream(false)
                .withTimestamps(false).exec(any())).thenAnswer(read -> {
                    ResultCallback.Adapter<Frame> output = read.getArgument(0);
                    output.onStart(stream);
                    output.onNext(new Frame(StreamType.STDOUT, bytes("Pi is ")));
                    Thread.currentThread().interrupt();
                    return output;
                });

        try {
            SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

            assertEquals(0, outcome.exitCode());
            verify(stream).close();
            verify(s3, never()).putObject(anyString(), anyString(), any(), anyString(), anyMap());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void logsThatCannotBeWrittenLeaveTheRunAsItEnded() {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDOUT, bytes("done\n")));
        when(s3.putObject(anyString(), anyString(), any(), anyString(), anyMap()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist", 404));

        SparkJobRunner.Outcome outcome = runner.run(job("s3://missing/emr/j-1/steps/s-1/"), progress);

        assertEquals(0, outcome.exitCode());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aDriverFlocisTeardownTookWritesNoLogs() {
        EmrServerlessSparkRunner runner = runner();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            runner.stopManagedContainers();
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

        assertTrue(outcome.stopped());
        verify(docker, never()).logContainerCmd(anyString());
        verify(s3, never()).putObject(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void theDriversOutputGoesToTheJobsCloudWatchLogStreamsWhileItRuns() {
        EmrServerlessSparkRunner runner = runner();
        String driver = "/applications/00f0000000000000/jobs/00f0000000000001/SPARK_DRIVER/";

        runner.run(job(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless", driver + "stdout", driver + "stderr")),
                progress);

        verify(logStreamer).attachByStreamForAccount("123456789012", "driver", "/aws/emr-serverless",
                driver + "stdout", driver + "stderr", "eu-west-1", "emr-serverless:00f0000000000001");
        verify(logStreamer, never()).attachConsoleOnly(anyString(), anyString());
    }

    @Test
    void withoutCloudWatchLogStreamsTheDriversOutputGoesOnlyToFlocisLog() {
        runner().run(job(Duration.ZERO), progress);

        verify(logStreamer).attachConsoleOnly("driver", "emr-serverless:00f0000000000001");
        verify(logStreamer, never()).attachByStreamForAccount(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRunWithoutALogFolderReadsNoOutput() {
        runner().run(job(Duration.ZERO), progress);

        verify(docker, never()).logContainerCmd(anyString());
        verify(lifecycle, never()).stop(anyString(), anyInt());
    }

    @Test
    void aLogFolderFollowsTheLogUriAfterOneSlash() {
        assertEquals("s3://logs/emr/j-1/steps/s-1/",
                EmrServerlessSparkRunner.logFolder("s3://logs/emr/", "j-1/steps/s-1/"));
        assertEquals("s3://logs/emr/j-1/steps/s-1/",
                EmrServerlessSparkRunner.logFolder("s3://logs/emr", "j-1/steps/s-1/"));
        assertEquals("s3://logs/j-1/steps/s-1/", EmrServerlessSparkRunner.logFolder("s3://logs//", "j-1/steps/s-1/"));
        assertNull(EmrServerlessSparkRunner.logFolder(null, "j-1/steps/s-1/"));
        assertNull(EmrServerlessSparkRunner.logFolder(" ", "j-1/steps/s-1/"));
    }

    @Test
    void driversAnEarlierProcessOfThisDeploymentLeftAreRemoved() {
        EmrServerlessSparkRunner runner = runner();
        when(config.port()).thenReturn(4566);
        List<Container> drivers = List.of(driver("left-behind", "4566"), driver("another-deployments", "4567"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(drivers);

        runner.removeLeftoverDrivers("emr-serverless");

        verify(lifecycle).removeIfExists("left-behind");
        verify(lifecycle, never()).removeIfExists("another-deployments");
    }

    // A runner whose driver container, "driver", starts at once and exits with code 0 unless a test says otherwise.
    private EmrServerlessSparkRunner runner() {
        return runner(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS));
    }

    private EmrServerlessSparkRunner runner(ContainerBuilder builder) {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("driver", Map.of()));
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(false);
        when(docker.inspectContainerCmd("driver").exec().getState().getExitCodeLong()).thenReturn(0L);
        return new EmrServerlessSparkRunner(builder, lifecycle, logStreamer,
                mock(LaunchedContainerAwsEnv.class), mock(LambdaExecutionRoleCredentials.class), s3, config,
                new MutableClock(), Duration.ZERO);
    }

    // What the driver wrote, as Docker gives it once asked for an ended container's output.
    private void driverWrote(Frame... frames) {
        when(docker.logContainerCmd("driver").withStdOut(true).withStdErr(true).withFollowStream(false)
                .withTimestamps(false).exec(any())).thenAnswer(read -> {
                    ResultCallback.Adapter<Frame> output = read.getArgument(0);
                    for (Frame frame : frames) {
                        output.onNext(frame);
                    }
                    output.onComplete();
                    return output;
                });
    }

    // The object the runner put at key, gunzipped.
    private String written(String bucket, String key) throws IOException {
        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(s3).putObject(eq(bucket), eq(key), data.capture(), eq("application/x-gzip"), eq(Map.of()));
        try (GZIPInputStream gunzip = new GZIPInputStream(new ByteArrayInputStream(data.getValue()))) {
            return new String(gunzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Container driver(String id, String owner) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr-serverless",
                ContainerStorageHelper.OWNER_LABEL, owner));
        return container;
    }

    private static SparkJobRunner.Job job(Duration timeout) {
        return job(List.of("s3://bucket/jobs/app.jar"), Map.of(), timeout);
    }

    private static SparkJobRunner.Job job(String logFolder) {
        return job(null, List.of("s3://bucket/jobs/app.jar"), Map.of(), Duration.ZERO, logFolder, null);
    }

    private static SparkJobRunner.Job job(SparkJobRunner.CloudWatchLogs cloudWatchLogs) {
        return job(null, List.of("s3://bucket/jobs/app.jar"), Map.of(), Duration.ZERO, null, cloudWatchLogs);
    }

    private static SparkJobRunner.Job job(List<String> application, Map<String, String> sparkDefaults) {
        return job(application, sparkDefaults, Duration.ZERO);
    }

    private static SparkJobRunner.Job job(List<String> application, Map<String, String> sparkDefaults,
                                          Duration timeout) {
        return job(null, application, sparkDefaults, timeout, null, null);
    }

    private static SparkJobRunner.Job job(String imageTemplate, List<String> application,
                                          Map<String, String> sparkDefaults, Duration timeout) {
        return job(imageTemplate, application, sparkDefaults, timeout, null, null);
    }

    private static SparkJobRunner.Job job(String imageTemplate, List<String> application,
                                          Map<String, String> sparkDefaults, Duration timeout, String logFolder,
                                          SparkJobRunner.CloudWatchLogs cloudWatchLogs) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                imageTemplate, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults, application,
                timeout, logFolder, cloudWatchLogs);
    }
}
