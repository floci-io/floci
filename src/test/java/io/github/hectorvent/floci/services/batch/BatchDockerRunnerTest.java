package io.github.hectorvent.floci.services.batch;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchDockerRunnerTest {

    @Test
    void resolveEndpointHostnameUsesHostGatewayWhenFlociRunsNatively() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        assertEquals("host.docker.internal", runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesConfiguredHostnameWhenFlociRunsInContainer() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals("floci.internal", runner(config(Optional.of("floci.internal")), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesEmbeddedDnsSuffixWhenContainerizedWithoutHostname() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals(EmbeddedDnsServer.DEFAULT_SUFFIX, runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    @Test
    void runLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());

        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));
        DockerClient dockerClient =
                mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, detector);

        BatchJob job = new BatchJob();
        job.setJobId("job-1");
        job.setJobName("my-job");
        job.setJobQueueName("my-queue");
        job.setJobDefinitionName("my-def");
        job.setContainerImage("busybox:stable");
        job.setRegion("us-east-1");
        job.setAccountId("000000000000");

        runner.run(job, 1);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "batch",
                "io.floci.resource-id", "job-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    @Test
    void stopJobStopsAnInFlightContainer() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());

        CountDownLatch containerStarted = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            containerStarted.countDown();
            return new ContainerLifecycleManager.ContainerInfo("container-id", Map.of());
        });
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning())
                .thenAnswer(invocation -> !stopped.get());
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(137L);
        doAnswer(invocation -> {
            stopped.set(true);
            return null;
        }).when(lifecycleManager).stopAndRemove(eq("container-id"), isNull());

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class));
        BatchJob job = job();
        AtomicReference<BatchRunResult> result = new AtomicReference<>();

        Thread runThread = Thread.startVirtualThread(() -> result.set(runner.run(job, 1)));
        assertTrue(containerStarted.await(2, TimeUnit.SECONDS));
        runner.stopJob(job.getJobId());
        runThread.join(2000);

        assertFalse(runThread.isAlive());
        assertEquals(137, result.get().exitCode());
        verify(lifecycleManager).stopAndRemove("container-id", null);
    }

    private BatchJob job() {
        BatchJob job = new BatchJob();
        job.setJobId("job-1");
        job.setJobName("my-job");
        job.setJobQueueName("my-queue");
        job.setJobDefinitionName("my-def");
        job.setContainerImage("busybox:stable");
        job.setRegion("us-east-1");
        job.setAccountId("000000000000");
        return job;
    }

    private BatchDockerRunner runner(EmulatorConfig config, ContainerDetector detector) {
        return new BatchDockerRunner(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                config,
                detector);
    }

    private EmulatorConfig config(Optional<String> hostname) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(hostname);
        return config;
    }
}
