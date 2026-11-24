package io.floci.aws.services.sagemaker;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.ModelResource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A state reset runs the container teardown, which shuts the worker pool down, then
 * {@code clear()}, and {@code afterReset()} last, even when the wipe or a {@code clear()}
 * failed. Shutdown runs only the teardown.
 */
class SageMakerEndpointManagerTest {

    @Test
    void teardownStopsThePoolAndAfterResetRestoresIt() {
        SageMakerEndpointManager manager = manager();
        assertTrue(manager.acceptsWork());

        manager.stopManagedContainers();
        assertFalse(manager.acceptsWork());

        // A reset whose wipe threw skips clear(); afterReset() alone must bring the pool back.
        manager.afterReset();
        assertTrue(manager.acceptsWork());
        manager.afterReset();
        assertTrue(manager.acceptsWork());

        manager.stopManagedContainers();
    }

    @Test
    void endpointStartsSubmittedAfterAResetStillRun() throws InterruptedException {
        SageMakerEndpointManager manager = manager();
        manager.stopManagedContainers();
        manager.clear();
        manager.afterReset();

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

    // Catches: an endpoint's container without its io.floci.service label, which leaves it outside
    // floci.docker.service-max-containers.sagemaker and its ResourceLimitExceeded error.
    @Test
    void anEndpointContainerCarriesTheSageMakerServiceLabel() throws InterruptedException {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenThrow(new IllegalStateException("stop after the spec"));
        SageMakerEndpointManager manager = new SageMakerEndpointManager(containerBuilder, lifecycleManager,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
        SageMakerService service = mock(SageMakerService.class);
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "labelled";
        endpoint.region = "us-east-1";
        endpoint.accountId = "000000000000";
        ModelResource model = new ModelResource();
        model.primaryContainer.put("Image", "example/serve:1");
        when(service.modelForEndpoint(endpoint)).thenReturn(model);
        CountDownLatch published = new CountDownLatch(1);
        doAnswer(invocation -> {
            published.countDown();
            return true;
        }).when(service).finalizeEndpointStart(endpoint);

        manager.startEndpointAsync(endpoint, service);

        assertTrue(published.await(5, TimeUnit.SECONDS));
        verify(specBuilder).withLabels(argThat(labels -> "sagemaker".equals(labels.get("io.floci.service"))
                && "labelled".equals(labels.get("io.floci.resource-id"))));
        manager.stopManagedContainers();
    }

    private static SageMakerEndpointManager manager() {
        return new SageMakerEndpointManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
    }
}
