package io.github.hectorvent.floci.services.sagemaker;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
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
class SageMakerEndpointManagerTest {
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final SageMakerService service = mock(SageMakerService.class);

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

    @Test
    void sameEndpointNameInAnotherAccountOrRegionGetsItsOwnContainer() throws InterruptedException {
        SageMakerEndpointManager manager = dockerBackedManager();
        List<EndpointResource> endpoints = List.of(
                endpoint("shared", "000000000000", "us-east-1"),
                endpoint("shared", "111111111111", "us-east-1"),
                endpoint("shared", "000000000000", "us-west-2"));
        CountDownLatch published = publishLatch(endpoints.size());

        // startCreated is unstubbed, so each start fails right after creating its container,
        // which is all this needs: the names it created and cleared beforehand.
        for (EndpointResource endpoint : endpoints) {
            manager.startEndpointAsync(endpoint, service, 0);
        }

        assertTrue(published.await(5, TimeUnit.SECONDS));
        ArgumentCaptor<ContainerSpec> specs = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycle, times(3)).create(specs.capture());
        Set<String> names = new HashSet<>();
        for (ContainerSpec spec : specs.getAllValues()) {
            names.add(spec.name());
        }
        assertEquals(3, names.size(), names.toString());
        assertTrue(names.contains("floci-aws-sagemaker-endpoint-111111111111-us-east-1-shared"), names.toString());
        ArgumentCaptor<String> cleared = ArgumentCaptor.forClass(String.class);
        verify(lifecycle, times(3)).removeIfExists(cleared.capture());
        assertEquals(names, new HashSet<>(cleared.getAllValues()));
        manager.stopManagedContainers();
    }

    @Test
    void stoppingAnEndpointLeavesTheSameNamedEndpointOfAnotherAccountRunning() throws Exception {
        HttpServer ping = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ping.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        ping.start();
        try {
            SageMakerEndpointManager manager = dockerBackedManager();
            when(lifecycle.create(any(ContainerSpec.class))).thenReturn("running-container");
            when(lifecycle.startCreated(any(), any())).thenReturn(new ContainerInfo("running-container",
                    Map.of(8080, new EndpointInfo("127.0.0.1", ping.getAddress().getPort()))));
            EndpointResource running = endpoint("shared", "000000000000", "us-east-1");
            CountDownLatch published = publishLatch(1);
            manager.startEndpointAsync(running, service, 0);
            assertTrue(published.await(10, TimeUnit.SECONDS));
            assertEquals("InService", running.endpointStatus);

            // The other account's endpoint never got a container, as when its start failed early.
            EndpointResource other = endpoint("shared", "111111111111", "us-east-1");
            manager.stopEndpoint(other);

            verify(lifecycle, never()).stopAndRemove("running-container", null);
            manager.stopManagedContainers();
        } finally {
            ping.stop(0);
        }
    }

    private CountDownLatch publishLatch(int starts) {
        ModelResource model = new ModelResource();
        model.primaryContainer = Map.of("Image", "busybox:stable");
        when(service.modelForEndpoint(any())).thenReturn(model);
        CountDownLatch published = new CountDownLatch(starts);
        doAnswer(invocation -> {
            published.countDown();
            return true;
        }).when(service).finalizeEndpointStart(any());
        return published;
    }

    private static EndpointResource endpoint(String name, String accountId, String region) {
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = name;
        endpoint.endpointArn = "arn:aws:sagemaker:" + region + ":" + accountId + ":endpoint/" + name;
        endpoint.accountId = accountId;
        endpoint.region = region;
        endpoint.generation = 1;
        return endpoint;
    }

    private static SageMakerEndpointManager manager() {
        return new SageMakerEndpointManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(ContainerDetector.class), mock(S3Service.class));
    }

    /** A manager that builds real container specs and drives a mocked lifecycle. */
    private SageMakerEndpointManager dockerBackedManager() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().sagemaker().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container");
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        return new SageMakerEndpointManager(builder, lifecycle, config, mock(ContainerDetector.class), mock(S3Service.class));
    }
}
