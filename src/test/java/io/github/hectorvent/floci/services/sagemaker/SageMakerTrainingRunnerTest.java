package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A state reset runs the container teardown, which shuts the worker pool down, and then
 * {@code clear()}. Shutdown runs only the teardown.
 */
class SageMakerTrainingRunnerTest {

    @Test
    void teardownStopsThePoolAndClearRestoresIt() {
        SageMakerTrainingRunner runner = runner();
        assertTrue(runner.acceptsWork());

        runner.stopManagedContainers();
        assertFalse(runner.acceptsWork());

        runner.clear();
        assertTrue(runner.acceptsWork());
        runner.clear();
        assertTrue(runner.acceptsWork());

        runner.stopManagedContainers();
    }

    @Test
    void trainingJobsSubmittedAfterAResetStillRun() throws InterruptedException {
        SageMakerTrainingRunner runner = runner();
        runner.stopManagedContainers();
        runner.clear();

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

    private static SageMakerTrainingRunner runner() {
        return new SageMakerTrainingRunner(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(ContainerDetector.class), mock(S3Service.class), new ObjectMapper());
    }
}
