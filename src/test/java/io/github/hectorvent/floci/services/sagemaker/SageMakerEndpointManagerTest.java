package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import org.junit.jupiter.api.Test;

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
class SageMakerEndpointManagerTest {

    @Test
    void teardownStopsThePoolAndClearRestoresIt() {
        SageMakerEndpointManager manager = manager();
        assertTrue(manager.acceptsWork());

        manager.stopManagedContainers();
        assertFalse(manager.acceptsWork());

        manager.clear();
        assertTrue(manager.acceptsWork());
        manager.clear();
        assertTrue(manager.acceptsWork());

        manager.stopManagedContainers();
    }

    @Test
    void endpointStartsSubmittedAfterAResetStillRun() throws InterruptedException {
        SageMakerEndpointManager manager = manager();
        manager.stopManagedContainers();
        manager.clear();

        SageMakerService service = mock(SageMakerService.class);
        CountDownLatch published = new CountDownLatch(1);
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "after-reset";
        endpoint.region = "us-east-1";
        // A latch rather than verify(timeout): finalizeEndpointStart is synchronized, and a timed
        // verify holds the mock's monitor while it polls, blocking the very call it waits for.
        doAnswer(invocation -> {
            published.countDown();
            return true;
        }).when(service).finalizeEndpointStart(endpoint);
        manager.startEndpointAsync(endpoint, service);

        // The mocked service has no model for the endpoint, so the start fails at once, and every
        // start that executes publishes its outcome: the publish proves the new pool ran the work.
        assertTrue(published.await(5, TimeUnit.SECONDS));
        manager.stopManagedContainers();
    }

    private static SageMakerEndpointManager manager() {
        return new SageMakerEndpointManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
    }
}
