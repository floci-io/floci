package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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

    @Test
    void sparkSubmitRunsLocallyOnFlocisS3ThenTheRunsOwnSettings() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/report.py");
        sparkSubmit.setEntryPointArguments(List.of("s3://bucket/input/", "s3://bucket/output/"));
        sparkSubmit.setSparkSubmitParameters("--conf spark.executor.cores=2 --py-files s3://bucket/jobs/lib.zip");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("spark.executor.cores", "4");
        defaults.put("spark.sql.shuffle.partitions", "8");

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(sparkSubmit, defaults), ENDPOINT);

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
    void anEntryPointWithoutParametersOrArgumentsFollowsFlocisSettings() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/app.jar");

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(sparkSubmit, Map.of()), ENDPOINT);

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
        EmrServerlessSparkRunner runner = runner();

        assertEquals("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest", runner.image("emr-7.5.0"));
        assertEquals("public.ecr.aws/emr-serverless/spark4/emr-spark-8.1.0:latest", runner.image("emr-spark-8.1.0"));
        when(config.services().emrserverless().imageTemplate())
                .thenReturn(Optional.of("registry.internal/emr-serverless/spark/%s:20250425"));
        assertEquals("registry.internal/emr-serverless/spark/emr-7.5.0:20250425", runner.image("emr-7.5.0"));
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
                    runner.stop(job.jobRunId());
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
    void aStopAfterTheRunHasEndedIsDropped() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ZERO);
        assertEquals(0, runner.run(job, progress).exitCode());

        runner.stop(job.jobRunId());

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
    void driversAnEarlierProcessOfThisDeploymentLeftAreRemoved() {
        EmrServerlessSparkRunner runner = runner();
        when(config.port()).thenReturn(4566);
        List<Container> drivers = List.of(driver("left-behind", "4566"), driver("another-deployments", "4567"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(drivers);

        runner.removeLeftoverDrivers();

        verify(lifecycle).removeIfExists("left-behind");
        verify(lifecycle, never()).removeIfExists("another-deployments");
    }

    // A runner whose driver container, "driver", starts at once and exits with code 0 unless a test says otherwise.
    private EmrServerlessSparkRunner runner() {
        when(config.services().emrserverless().imageTemplate()).thenReturn(Optional.empty());
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("driver", Map.of()));
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(false);
        when(docker.inspectContainerCmd("driver").exec().getState().getExitCodeLong()).thenReturn(0L);
        return new EmrServerlessSparkRunner(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS), lifecycle,
                mock(ContainerLogStreamer.class), mock(LaunchedContainerAwsEnv.class),
                mock(LambdaExecutionRoleCredentials.class), config, new MutableClock(), Duration.ZERO);
    }

    private static Container driver(String id, String owner) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr-serverless",
                ContainerStorageHelper.OWNER_LABEL, owner));
        return container;
    }

    private static SparkJobRunner.Job job(Duration timeout) {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/app.jar");
        return job(sparkSubmit, Map.of(), timeout);
    }

    private static SparkJobRunner.Job job(JobDriver.SparkSubmit sparkSubmit, Map<String, String> sparkDefaults) {
        return job(sparkSubmit, sparkDefaults, Duration.ZERO);
    }

    private static SparkJobRunner.Job job(JobDriver.SparkSubmit sparkSubmit, Map<String, String> sparkDefaults,
                                          Duration timeout) {
        return new SparkJobRunner.Job("00f0000000000001", "00f0000000000000", "123456789012", "eu-west-1",
                "emr-7.5.0", "arn:aws:iam::123456789012:role/job", sparkSubmit, sparkDefaults, timeout);
    }
}
