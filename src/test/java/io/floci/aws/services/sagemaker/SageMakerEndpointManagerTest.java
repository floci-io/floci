package io.floci.aws.services.sagemaker;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A state reset runs the container teardown, which shuts the worker pool down, then
 * {@code clear()}, and {@code afterReset()} last, even when the wipe or a {@code clear()}
 * failed. Shutdown runs only the teardown.
 */
// One test serves /ping on a port it holds for the test's length.
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
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
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        SageMakerEndpointManager manager = new SageMakerEndpointManager(containerBuilder, lifecycleManager,
                config, mock(ContainerDetector.class), mock(S3Service.class));
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
        String name = SageMakerEndpointManager.containerName(config, "000000000000", "us-east-1", "labelled");
        verify(lifecycleManager).removeIfExists(name);
        verify(specBuilder).withName(name);
        manager.stopManagedContainers();
    }

    /** #5664: endpoints of one name in two accounts or Regions get containers of their own. */
    @Test
    void sameNamedEndpointsOfOtherAccountsOrRegionsGetContainersOfTheirOwn() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);

        Set<String> names = Set.of(
                SageMakerEndpointManager.containerName(config, "111111111111", "us-east-1", "ep"),
                SageMakerEndpointManager.containerName(config, "222222222222", "us-east-1", "ep"),
                SageMakerEndpointManager.containerName(config, "111111111111", "eu-west-1", "ep"));

        assertEquals(3, names.size());
    }

    /** Two endpoints of one name in two accounts are tracked apart: stopping one leaves the other's container. */
    @Test
    void stoppingOneOfTwoSameNamedEndpointsLeavesTheOthersContainer() throws Exception {
        HttpServer ping = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ping.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        ping.start();
        try {
            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.create(any())).thenReturn("container-of-111", "container-of-222");
            when(lifecycleManager.startCreated(anyString(), any())).thenAnswer(invocation -> new ContainerInfo(
                    invocation.getArgument(0), Map.of(8080, new EndpointInfo("127.0.0.1", ping.getAddress().getPort()))));
            SageMakerEndpointManager manager = new SageMakerEndpointManager(containerBuilder, lifecycleManager,
                    mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
            SageMakerService service = mock(SageMakerService.class);
            ModelResource model = new ModelResource();
            model.primaryContainer.put("Image", "example/serve:1");
            when(service.modelForEndpoint(any())).thenReturn(model);
            for (String account : List.of("111111111111", "222222222222")) {
                CountDownLatch published = new CountDownLatch(1);
                doAnswer(invocation -> {
                    published.countDown();
                    return true;
                }).when(service).finalizeEndpointStart(any());
                manager.startEndpointAsync(sharedNameEndpoint(account), service);
                assertTrue(published.await(5, TimeUnit.SECONDS));
            }

            manager.stopEndpoint(sharedNameEndpoint("111111111111"));

            verify(lifecycleManager).stopAndRemove("container-of-111", null);
            verify(lifecycleManager, never()).stopAndRemove(eq("container-of-222"), any());
            manager.stopManagedContainers();
        } finally {
            ping.stop(0);
        }
    }

    /** The stored record of an endpoint named "shared" while it starts, before a container is recorded on it. */
    private static EndpointResource sharedNameEndpoint(String accountId) {
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "shared";
        endpoint.region = "us-east-1";
        endpoint.accountId = accountId;
        return endpoint;
    }

    /** A crash can leave a container no record names; stopping the endpoint removes it by the endpoint's name. */
    @Test
    void stoppingAnEndpointNoContainerIsRecordedForRemovesItsContainerByName() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        SageMakerEndpointManager manager = new SageMakerEndpointManager(mock(ContainerBuilder.class),
                lifecycleManager, config, mock(ContainerDetector.class), mock(S3Service.class));
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "leftover";
        endpoint.region = "us-east-1";
        endpoint.accountId = "111111111111";

        manager.stopEndpoint(endpoint);

        verify(lifecycleManager).removeIfExists(
                SageMakerEndpointManager.containerName(config, "111111111111", "us-east-1", "leftover"));
        verify(lifecycleManager, never()).stopAndRemove(any(), any());
    }

    private static SageMakerEndpointManager manager() {
        return new SageMakerEndpointManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
    }
}
