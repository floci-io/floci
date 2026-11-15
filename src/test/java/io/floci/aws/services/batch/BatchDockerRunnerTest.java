package io.floci.aws.services.batch;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.batch.model.BatchJob;
import io.floci.aws.services.batch.model.BatchNodeExecution;
import io.floci.aws.services.batch.model.BatchRunResult;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
        EmulatorConfig config = batchConfig();

        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        DockerClient dockerClient =
                mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, detector, null, null);

        BatchJob job = new BatchJob();
        job.setJobId("job-1");
        job.setJobName("my-job");
        job.setJobQueueName("my-queue");
        job.setJobDefinitionName("my-def");
        job.setContainerImage("busybox:stable");
        job.setRegion("us-east-1");
        job.setAccountId("000000000000");

        runner.run(job, 1, null);

        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.captor();
        verify(builder).withLabels(labels.capture());
        assertEquals(Map.of(
                "io.floci", "aws",
                "io.floci.service", "batch",
                "io.floci.resource-id", "job-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1",
                "io.floci.owner", "4566"), withoutRunLabel(labels.getValue()));
        // The run label lets a later run tell this run's containers from a crash's leftovers.
        assertTrue(labels.getValue().containsKey(BatchDockerRunner.RUN_LABEL));
        // A job's JVMs trust Floci's CA as well.
        verify(builder).withJavaTrustStore();
    }

    @Test
    void stopJobStopsAnInFlightContainer() throws Exception {
        EmulatorConfig config = batchConfig();

        CountDownLatch containerStarted = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        when(lifecycleManager.startCreated(eq("container-id"), any())).thenAnswer(invocation -> {
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
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class), null, null);
        BatchJob job = job();
        AtomicReference<BatchRunResult> result = new AtomicReference<>();

        Thread runThread = Thread.startVirtualThread(() -> result.set(runner.run(job, 1, null)));
        assertTrue(containerStarted.await(2, TimeUnit.SECONDS));
        runner.requestStop(job.getJobId());
        runner.stopJob(job.getJobId());
        runThread.join(2000);

        assertFalse(runThread.isAlive());
        assertEquals(137, result.get().exitCode());
        verify(lifecycleManager).stopAndRemove("container-id", null);
    }

    @Test
    void stopJobAfterRequestIsClearedDoesNotRecreateStopMarker() {
        EmulatorConfig config = config(Optional.empty());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(dockerClient.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class), null, null);

        runner.requestStop("job-1");
        runner.clearStopRequest("job-1");
        runner.stopJob("job-1");
        BatchRunResult result = runner.run(job(), 1, null);

        assertEquals(0, result.exitCode());
        verify(lifecycleManager).create(any());
        verify(lifecycleManager).startCreated(eq("container-id"), any());
    }

    @Test
    void stopJobStopsEveryInFlightNodeContainer() throws Exception {
        EmulatorConfig config = config(Optional.empty());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);

        CountDownLatch containersStarted = new CountDownLatch(2);
        AtomicInteger containerSequence = new AtomicInteger();
        Set<String> stoppedContainers = ConcurrentHashMap.newKeySet();
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any()))
                .thenAnswer(invocation -> "container-" + containerSequence.getAndIncrement());
        when(lifecycleManager.startCreated(anyString(), any())).thenAnswer(invocation -> {
            containersStarted.countDown();
            return new ContainerLifecycleManager.ContainerInfo(invocation.getArgument(0), Map.of());
        });
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        for (int i = 0; i < 2; i++) {
            String containerId = "container-" + i;
            when(dockerClient.inspectContainerCmd(containerId).exec().getState().getRunning())
                    .thenAnswer(invocation -> !stoppedContainers.contains(containerId));
            when(dockerClient.inspectContainerCmd(containerId).exec().getState().getExitCodeLong()).thenReturn(137L);
            doAnswer(invocation -> {
                stoppedContainers.add(containerId);
                return null;
            }).when(lifecycleManager).stopAndRemove(eq(containerId), isNull());
        }
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), config, mock(ContainerDetector.class), null, null);
        BatchJob job = job();
        BatchNodeExecution firstNode = node(0);
        BatchNodeExecution secondNode = node(1);
        AtomicReference<BatchRunResult> firstResult = new AtomicReference<>();
        AtomicReference<BatchRunResult> secondResult = new AtomicReference<>();

        Thread firstThread = Thread.startVirtualThread(() -> firstResult.set(runner.run(job, 1, firstNode, null)));
        Thread secondThread = Thread.startVirtualThread(() -> secondResult.set(runner.run(job, 1, secondNode, null)));
        assertTrue(containersStarted.await(2, TimeUnit.SECONDS));
        runner.requestStop(job.getJobId());
        runner.stopJob(job.getJobId());
        firstThread.join(2000);
        secondThread.join(2000);

        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(137, firstResult.get().exitCode());
        assertEquals(137, secondResult.get().exitCode());
        verify(lifecycleManager).stopAndRemove("container-0", null);
        verify(lifecycleManager).stopAndRemove("container-1", null);
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

    private BatchNodeExecution node(int nodeIndex) {
        BatchNodeExecution node = new BatchNodeExecution();
        node.setNodeIndex(nodeIndex);
        node.setContainerImage("busybox:stable");
        return node;
    }

    @Test
    void aJobInAVpcJoinsItsSubnetsNetworkAndGivesTheAddressBackOnceItsContainerIsGone() {
        Ec2Service ec2 = mock(Ec2Service.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2).runWithSubnetReferencesHeld(eq("us-east-1"), any());
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-a");
        subnet.setVpcId("vpc-1");
        when(ec2.describeSubnets("us-east-1", List.of("subnet-a"), Map.of())).thenReturn(List.of(subnet));
        when(ec2.allocateVpcPrivateIp("us-east-1", "subnet-a")).thenReturn(Optional.of("10.0.1.30"));
        when(ec2.attachContainerToVpc("us-east-1", "vpc-1", "subnet-a", "container-id", "10.0.1.30"))
                .thenReturn(Optional.of("10.0.1.30"));
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("container-id");
        DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectContainerResponse exited = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(exited.getState().getRunning()).thenReturn(false);
        when(exited.getState().getExitCodeLong()).thenReturn(0L);
        AtomicBoolean removed = new AtomicBoolean();
        AtomicBoolean heldWhileRunning = new AtomicBoolean();
        BatchDockerRunner[] runnerRef = new BatchDockerRunner[1];
        when(dockerClient.inspectContainerCmd("container-id").exec()).thenAnswer(invocation -> {
            if (removed.get()) {
                throw new NotFoundException("gone");
            }
            heldWhileRunning.compareAndSet(false, runnerRef[0].hasSubnetDependency("us-east-1", "subnet-a"));
            return exited;
        });
        doAnswer(invocation -> {
            removed.set(true);
            return null;
        }).when(lifecycleManager).stopAndRemove(eq("container-id"), any());
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        BatchDockerRunner runner = new BatchDockerRunner(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), batchConfig(), mock(ContainerDetector.class), regionResolver, ec2);
        runnerRef[0] = runner;

        BatchRunResult result = runner.run(job(), 1, "subnet-a");

        assertEquals(0, result.exitCode());
        // The job's command is the entrypoint, so the container is on the VPC network before it starts.
        InOrder order = inOrder(ec2, lifecycleManager);
        order.verify(ec2).attachContainerToVpc("us-east-1", "vpc-1", "subnet-a", "container-id", "10.0.1.30");
        order.verify(lifecycleManager).startCreated(eq("container-id"), any());
        assertTrue(heldWhileRunning.get(), "the job's address holds its subnet while it runs");
        verify(ec2).releaseVpcPrivateIp("us-east-1", "subnet-a", "10.0.1.30");
        assertFalse(runner.hasSubnetDependency("us-east-1", "subnet-a"));
    }

    private static Map<String, String> withoutRunLabel(Map<String, String> labels) {
        Map<String, String> rest = new HashMap<>(labels);
        rest.remove(BatchDockerRunner.RUN_LABEL);
        return rest;
    }

    private static EmulatorConfig batchConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.dockerNetwork()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());
        return config;
    }

    private BatchDockerRunner runner(EmulatorConfig config, ContainerDetector detector) {
        return new BatchDockerRunner(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                config,
                detector,
                null,
                null);
    }

    private EmulatorConfig config(Optional<String> hostname) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(hostname);
        return config;
    }
}
