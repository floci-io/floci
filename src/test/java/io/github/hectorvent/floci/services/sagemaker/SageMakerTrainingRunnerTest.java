package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import io.github.hectorvent.floci.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A state reset runs the container teardown, which shuts the worker pool down, then
 * {@code clear()}, and {@code afterReset()} last, even when the wipe or a {@code clear()}
 * failed. Shutdown runs only the teardown.
 */
class SageMakerTrainingRunnerTest {
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final S3Service s3 = mock(S3Service.class);

    @Test
    void teardownStopsThePoolAndAfterResetRestoresIt() {
        SageMakerTrainingRunner runner = runner();
        assertTrue(runner.acceptsWork());

        runner.stopManagedContainers();
        assertFalse(runner.acceptsWork());

        // A reset whose wipe threw skips clear(); afterReset() alone must bring the pool back.
        runner.afterReset();
        assertTrue(runner.acceptsWork());
        runner.afterReset();
        assertTrue(runner.acceptsWork());

        runner.stopManagedContainers();
    }

    @Test
    void trainingJobsSubmittedAfterAResetStillRun() throws InterruptedException {
        SageMakerTrainingRunner runner = runner();
        runner.stopManagedContainers();
        runner.clear();
        runner.afterReset();

        SageMakerService service = mock(SageMakerService.class);
        CountDownLatch reported = new CountDownLatch(1);
        TrainingJobResource job = new TrainingJobResource();
        job.trainingJobName = "after-reset";
        job.region = "us-east-1";
        job.algorithmSpecification = Map.of("TrainingImage", "busybox:stable");
        // A latch rather than verify(timeout): updateTrainingJob is synchronized, and a timed
        // verify holds the mock's monitor while it polls, blocking the very call it waits for.
        doAnswer(invocation -> {
            reported.countDown();
            return true;
        }).when(service).updateTrainingJob(job);
        runner.runAsync(job, service);

        // The mocked Docker helpers fail the run at once, and every run that executes reports its
        // outcome, so the report proves the new pool accepted and ran the work.
        assertTrue(reported.await(5, TimeUnit.SECONDS));
        runner.stopManagedContainers();
    }

    @Test
    void trainingLogsAreForwardedToTheJobsAccount() throws InterruptedException {
        SageMakerTrainingRunner runner = dockerBackedRunner();
        TrainingJobResource job = job("account-logs", "111111111111", "us-east-1");
        SageMakerService service = mock(SageMakerService.class);
        // Two reports: the start of training, then the outcome once the unstubbed inspect fails.
        CountDownLatch reported = new CountDownLatch(2);
        doAnswer(invocation -> {
            reported.countDown();
            return true;
        }).when(service).updateTrainingJob(job);

        runner.runAsync(job, service, 0);

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        verify(logStreamer).attachForAccount("111111111111", "container", "/aws/sagemaker/TrainingJobs",
                "account-logs/algo-1", "us-east-1", "sagemaker:account-logs");
        runner.stopManagedContainers();
    }

    @Test
    void sameTrainingJobNameInAnotherAccountOrRegionGetsItsOwnContainer() throws InterruptedException {
        SageMakerTrainingRunner runner = dockerBackedRunner();
        SageMakerService service = mock(SageMakerService.class);
        Semaphore reports = reportsOf(service);

        // Inspect is unstubbed, so each run fails once its container is running, which is all this
        // needs: the names it created and cleared beforehand. One at a time, since the upload
        // fixture is not thread-safe.
        for (TrainingJobResource job : List.of(
                job("shared", "000000000000", "us-east-1"),
                job("shared", "111111111111", "us-east-1"),
                job("shared", "000000000000", "us-west-2"))) {
            runner.runAsync(job, service, 0);
            assertTrue(reports.tryAcquire(2, 5, TimeUnit.SECONDS));
        }

        ArgumentCaptor<ContainerSpec> specs = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycle, times(3)).create(specs.capture());
        Set<String> names = new HashSet<>();
        for (ContainerSpec spec : specs.getAllValues()) {
            names.add(spec.name());
        }
        assertEquals(3, names.size(), names.toString());
        assertTrue(names.contains("floci-aws-sagemaker-training-111111111111-us-east-1-shared"), names.toString());
        ArgumentCaptor<String> cleared = ArgumentCaptor.forClass(String.class);
        verify(lifecycle, times(3)).removeIfExists(cleared.capture());
        assertEquals(names, new HashSet<>(cleared.getAllValues()));
        runner.stopManagedContainers();
    }

    @Test
    void stoppingATrainingJobLeavesTheSameNamedJobOfAnotherAccountRunning() throws InterruptedException {
        SageMakerTrainingRunner runner = dockerBackedRunner();
        AtomicBoolean running = new AtomicBoolean(true);
        inspectReports(running, 3);
        SageMakerService service = mock(SageMakerService.class);
        Semaphore reports = reportsOf(service);
        TrainingJobResource job = job("shared", "000000000000", "us-east-1");
        runner.runAsync(job, service, 0);
        assertTrue(reports.tryAcquire(1, 5, TimeUnit.SECONDS));

        // The other account's job never got a container, as when its start failed early.
        runner.stop(job("shared", "111111111111", "us-east-1"));

        verify(lifecycle, never()).stopAndRemove("container", null);
        running.set(false);
        assertTrue(reports.tryAcquire(1, 5, TimeUnit.SECONDS), "the running job must still report its own outcome");
        assertEquals("Failed", job.trainingJobStatus);
        assertEquals("Container exited with code 3", job.failureReason);
        runner.stopManagedContainers();
    }

    @Test
    void aTrainingContainerRemovedBeforeItExitsIsNotReportedAsAnExitCode() throws InterruptedException {
        SageMakerTrainingRunner runner = dockerBackedRunner();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("container")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("No such container: container"));
        SageMakerService service = mock(SageMakerService.class);
        Semaphore reports = reportsOf(service);
        TrainingJobResource job = job("vanished", "000000000000", "us-east-1");

        runner.runAsync(job, service, 0);

        assertTrue(reports.tryAcquire(2, 5, TimeUnit.SECONDS));
        assertEquals("Failed", job.trainingJobStatus);
        assertFalse(job.failureReason.contains("exited with code"), job.failureReason);
        assertTrue(job.failureReason.contains("removed"), job.failureReason);
        runner.stopManagedContainers();
    }

    @Test
    void exceedingMaxRuntimeStopsTheJobAndKeepsItsModelArtifacts() throws Exception {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        CopyArchiveFromContainerCmd copyModel = mock(CopyArchiveFromContainerCmd.class);
        SageMakerTrainingRunner runner = dockerBackedRunner();

        TrainingJobResource job = runPastMaxRuntime(runner, stop, copyModel);

        assertEquals("Stopped", job.trainingJobStatus);
        assertEquals("MaxRuntimeExceeded", job.secondaryStatus);
        assertNull(job.failureReason);
        assertEquals("s3://bucket/output/slow/output/model.tar.gz", job.modelArtifactsS3ModelArtifacts);
        // SIGTERM with SageMaker's 120 s window to save the model, then the upload, and only then removal.
        InOrder order = inOrder(stop, copyModel, s3, lifecycle);
        order.verify(stop).withTimeout(120);
        order.verify(stop).exec();
        order.verify(copyModel).exec();
        order.verify(s3).putObject(eq("bucket"), eq("output/slow/output/model.tar.gz"), any(byte[].class),
                eq("application/x-tar"), eq(Map.of()));
        order.verify(lifecycle).stopAndRemove("container", null);
        runner.stopManagedContainers();
    }

    @Test
    void aContainerThatExitsAsItsRuntimeRunsOutStillKeepsItsModelArtifacts() throws Exception {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenThrow(new NotModifiedException("Container already stopped"));
        SageMakerTrainingRunner runner = dockerBackedRunner();

        TrainingJobResource job = runPastMaxRuntime(runner, stop, mock(CopyArchiveFromContainerCmd.class));

        assertEquals("Stopped", job.trainingJobStatus);
        assertEquals("MaxRuntimeExceeded", job.secondaryStatus);
        assertNull(job.failureReason);
        assertEquals("s3://bucket/output/slow/output/model.tar.gz", job.modelArtifactsS3ModelArtifacts);
        runner.stopManagedContainers();
    }

    @Test
    void aGracefulStopThatTimesOutStillStopsTheJobAndKeepsItsModelArtifacts() throws Exception {
        // floci.docker.api-timeout-seconds below the 120 s grace: the client gives up on a container
        // that ignores SIGTERM before Docker answers the stop, so it is killed once the window ends.
        AtomicLong stoppedAt = new AtomicLong();
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenAnswer(invocation -> {
            stoppedAt.set(System.nanoTime());
            throw new RuntimeException(new SocketTimeoutException("Read timed out"));
        });
        AtomicLong killedAt = new AtomicLong();
        KillContainerCmd kill = mock(KillContainerCmd.class);
        when(docker.killContainerCmd("container")).thenReturn(kill);
        when(kill.exec()).thenAnswer(invocation -> {
            killedAt.set(System.nanoTime());
            return null;
        });
        CopyArchiveFromContainerCmd copyModel = mock(CopyArchiveFromContainerCmd.class);
        AtomicBoolean readWhileRunning = new AtomicBoolean();
        SageMakerTrainingRunner runner = dockerBackedRunner(Duration.ofMillis(300), Duration.ofMillis(10));

        TrainingJobResource job = runPastMaxRuntime(runner, stop, copyModel, () -> killedAt.get() == 0,
                readWhileRunning);

        assertEquals("Stopped", job.trainingJobStatus);
        assertEquals("MaxRuntimeExceeded", job.secondaryStatus);
        assertNull(job.failureReason);
        assertEquals("s3://bucket/output/slow/output/model.tar.gz", job.modelArtifactsS3ModelArtifacts);
        assertFalse(readWhileRunning.get(), "the model must not be read while the container still runs");
        assertTrue(killedAt.get() - stoppedAt.get() >= Duration.ofMillis(150).toNanos(),
                "the container must be killed only once the grace window has ended");
        InOrder order = inOrder(stop, kill, copyModel, lifecycle);
        order.verify(stop).exec();
        order.verify(kill).exec();
        order.verify(copyModel).exec();
        order.verify(lifecycle).stopAndRemove("container", null);
        runner.stopManagedContainers();
    }

    @Test
    void aContainerStillSavingAfterAFailedStopHasItsModelReadOnlyOnceItExits() throws Exception {
        // The client gave up on the stop while the container was still writing /opt/ml/model; it
        // exits 200 ms later, well inside the window.
        AtomicLong exitsAt = new AtomicLong(Long.MAX_VALUE);
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(stop.exec()).thenAnswer(invocation -> {
            exitsAt.set(System.nanoTime() + Duration.ofMillis(200).toNanos());
            throw new RuntimeException(new SocketTimeoutException("Read timed out"));
        });
        KillContainerCmd kill = mock(KillContainerCmd.class);
        when(docker.killContainerCmd("container")).thenReturn(kill);
        CopyArchiveFromContainerCmd copyModel = mock(CopyArchiveFromContainerCmd.class);
        AtomicBoolean readWhileRunning = new AtomicBoolean();
        SageMakerTrainingRunner runner = dockerBackedRunner(Duration.ofSeconds(10), Duration.ofMillis(10));

        TrainingJobResource job = runPastMaxRuntime(runner, stop, copyModel,
                () -> System.nanoTime() < exitsAt.get(), readWhileRunning);

        assertEquals("Stopped", job.trainingJobStatus);
        assertEquals("MaxRuntimeExceeded", job.secondaryStatus);
        assertNull(job.failureReason);
        assertEquals("s3://bucket/output/slow/output/model.tar.gz", job.modelArtifactsS3ModelArtifacts);
        assertFalse(readWhileRunning.get(), "the model must not be read while the container still runs");
        verify(kill, never()).exec();
        runner.stopManagedContainers();
    }

    /** Runs a job whose container is still running when its one-second MaxRuntimeInSeconds is up. */
    private TrainingJobResource runPastMaxRuntime(SageMakerTrainingRunner runner, StopContainerCmd stop,
                                                  CopyArchiveFromContainerCmd copyModel) throws Exception {
        return runPastMaxRuntime(runner, stop, copyModel, () -> true, new AtomicBoolean());
    }

    /**
     * As above, with the container running for as long as {@code running} says so;
     * {@code readWhileRunning} records whether the model was read from a container still running.
     */
    private TrainingJobResource runPastMaxRuntime(SageMakerTrainingRunner runner, StopContainerCmd stop,
                                                  CopyArchiveFromContainerCmd copyModel, BooleanSupplier running,
                                                  AtomicBoolean readWhileRunning) throws Exception {
        inspectReports(running, 0);
        when(docker.stopContainerCmd("container")).thenReturn(stop);
        when(docker.copyArchiveFromContainerCmd("container", "/opt/ml/model")).thenReturn(copyModel);
        when(copyModel.exec()).thenAnswer(invocation -> {
            readWhileRunning.set(running.getAsBoolean());
            return new ByteArrayInputStream(tar("model/model.txt", "saved on SIGTERM"));
        });
        SageMakerService service = mock(SageMakerService.class);
        Semaphore reports = reportsOf(service);
        TrainingJobResource job = job("slow", "000000000000", "us-east-1");
        job.stoppingCondition = Map.of("MaxRuntimeInSeconds", 1);

        runner.runAsync(job, service, 0);

        assertTrue(reports.tryAcquire(2, 10, TimeUnit.SECONDS));
        return job;
    }

    private static byte[] tar(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(data.length);
            tar.putArchiveEntry(entry);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static Semaphore reportsOf(SageMakerService service) {
        Semaphore reports = new Semaphore(0);
        doAnswer(invocation -> {
            reports.release();
            return true;
        }).when(service).updateTrainingJob(any());
        return reports;
    }

    private void inspectReports(AtomicBoolean running, long exitCode) {
        inspectReports(running::get, exitCode);
    }

    private void inspectReports(BooleanSupplier running, long exitCode) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(docker.inspectContainerCmd("container")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getState()).thenReturn(state);
        when(state.getRunning()).thenAnswer(invocation -> running.getAsBoolean());
        when(state.getExitCodeLong()).thenReturn(exitCode);
    }

    private static SageMakerTrainingRunner runner() {
        return new SageMakerTrainingRunner(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(ContainerDetector.class), mock(S3Service.class), new ObjectMapper(),
                mock(SageMakerGpuResolver.class));
    }

    /** A runner that builds real container specs and drives a mocked lifecycle and Docker client. */
    private SageMakerTrainingRunner dockerBackedRunner() {
        EmulatorConfig config = dockerBackedConfig();
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        return new SageMakerTrainingRunner(builder, lifecycle, logStreamer, config, mock(ContainerDetector.class),
                s3, new ObjectMapper(), mock(SageMakerGpuResolver.class));
    }

    /** As {@link #dockerBackedRunner()}, with a shorter MaxRuntime stop window and exit poll. */
    private SageMakerTrainingRunner dockerBackedRunner(Duration maxRuntimeStopGrace, Duration exitPollInterval) {
        EmulatorConfig config = dockerBackedConfig();
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        return new SageMakerTrainingRunner(builder, lifecycle, logStreamer, config, mock(ContainerDetector.class),
                s3, new ObjectMapper(), mock(SageMakerGpuResolver.class), maxRuntimeStopGrace, exitPollInterval);
    }

    private EmulatorConfig dockerBackedConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().sagemaker().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        new DockerArchiveUploadFixture(docker, "container");
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container");
        when(lifecycle.getDockerClient()).thenReturn(docker);
        return config;
    }

    private static TrainingJobResource job(String name, String accountId, String region) {
        TrainingJobResource job = new TrainingJobResource();
        job.trainingJobName = name;
        job.trainingJobArn = "arn:aws:sagemaker:" + region + ":" + accountId + ":training-job/" + name;
        job.accountId = accountId;
        job.region = region;
        job.algorithmSpecification = Map.of("TrainingImage", "busybox:stable");
        job.outputDataConfig = Map.of("S3OutputPath", "s3://bucket/output");
        return job;
    }
}
