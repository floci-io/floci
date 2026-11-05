package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsStablePortRolloutServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "stable-ports";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static Stream<Arguments> stablePortDeployments() {
        return Stream.of(PublicationMode.STABLE_AWSVPC, PublicationMode.STABLE_AWSVPC_IMPLICIT,
                        PublicationMode.BRIDGE_FIXED, PublicationMode.HOST_FIXED)
                .flatMap(mode -> Stream.of(Arguments.of(mode, true), Arguments.of(mode, false)));
    }

    @ParameterizedTest
    @MethodSource("stablePortDeployments")
    void deploymentReleasesStablePortBeforeStartingReplacement(PublicationMode publication, boolean force) {
        try (Harness harness = new Harness(publication)) {
            EcsServiceModel service = harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            String previousDeployment = service.getDeploymentId();

            String expectedDefinition = original.getTaskDefinitionArn();
            if (force) {
                harness.forceDeployment("web");
            } else {
                TaskDefinition revision = harness.registerTaskDefinition(List.of(new PortMapping(8080, 8080, "tcp")));
                expectedDefinition = revision.getTaskDefinitionArn();
                harness.ecs.updateService(CLUSTER, "web", expectedDefinition, null, null, REGION);
            }
            harness.ecs.reconcileServices();

            assertEquals("STOPPED", original.getDesiredStatus());
            assertEquals(0, harness.runningTasks("web").size());
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
            harness.ecs.reconcileServices();

            EcsTask replacement = harness.runningTasks("web").getFirst();
            assertNotEquals(original.getTaskArn(), replacement.getTaskArn());
            assertNotEquals(previousDeployment, replacement.getDeploymentId());
            assertEquals(expectedDefinition, replacement.getTaskDefinitionArn());
            assertEquals("ServiceSchedulerInitiated", original.getStopCode());
        }
    }

    @ParameterizedTest
    @EnumSource(value = PublicationMode.class, names = {"MOCK", "DYNAMIC_AWSVPC", "PRIVATE_AWSVPC", "BRIDGE_DYNAMIC"})
    void zeroMinimumKeepsReplacementFirstWithoutFixedPorts(PublicationMode publication) {
        try (Harness harness = new Harness(publication)) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();

            harness.forceDeployment("web");
            harness.ecs.reconcileServices();

            assertEquals("RUNNING", original.getLastStatus());
            assertEquals(2, harness.runningTasks("web").size());
        }
    }

    private static Stream<List<PortMapping>> nonconflictingPorts() {
        return Stream.of(List.of(new PortMapping(9090, 9090, "tcp")), List.of());
    }

    @ParameterizedTest
    @MethodSource("nonconflictingPorts")
    void zeroMinimumKeepsReplacementFirstWhenNewRevisionDoesNotReusePort(List<PortMapping> ports) {
        try (Harness harness = new Harness()) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            TaskDefinition revision = harness.registerTaskDefinition(ports);

            harness.ecs.updateService(CLUSTER, "web", revision.getTaskDefinitionArn(), null, null, REGION);
            harness.ecs.reconcileServices();

            assertEquals("RUNNING", original.getLastStatus());
            assertEquals(2, harness.runningTasks("web").size());
        }
    }

    @Test
    void protocolChangeStillWaitsForTheCurrentTcpPublisherToReleaseTheHostPort() {
        try (Harness harness = new Harness()) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            TaskDefinition revision = harness.registerTaskDefinition(List.of(new PortMapping(8080, 8080, "udp")));

            harness.ecs.updateService(CLUSTER, "web", revision.getTaskDefinitionArn(), null, null, REGION);
            harness.ecs.reconcileServices();

            assertEquals("STOPPED", original.getDesiredStatus());
            assertEquals(0, harness.runningTasks("web").size());
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
            harness.ecs.reconcileServices();
            assertEquals(revision.getTaskDefinitionArn(), harness.runningTasks("web").getFirst().getTaskDefinitionArn());
        }
    }

    @Test
    void retainedStoppingTaskIsRetriedBeforeAnyReplacementIsPlaced() {
        try (Harness harness = new Harness()) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            AtomicBoolean removalAllowed = new AtomicBoolean();
            AtomicInteger stops = new AtomicInteger();
            doAnswer(invocation -> {
                EcsTaskHandle handle = invocation.getArgument(0);
                stops.incrementAndGet();
                if (removalAllowed.get()) {
                    handle.recordContainerRemoved("app");
                }
                // Exit codes alone do not confirm that Docker released the port.
                return Map.of("app", 0);
            }).when(harness.manager).stopTaskAndCollectExitCodes(any());

            harness.forceDeployment("web");
            harness.ecs.reconcileServices();
            await().atMost(TIMEOUT).until(() -> stops.get() > 0);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                harness.ecs.reconcileServices();
                assertEquals("STOPPING", original.getLastStatus());
                assertEquals(0, harness.runningTasks("web").size());
                assertTrue(stops.get() >= 2, "incomplete removal is retried");
            });
            verify(harness.manager, times(1)).startTask(any(), any(), any(), anyString());

            removalAllowed.set(true);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                harness.ecs.reconcileServices();
                assertEquals("STOPPED", original.getLastStatus());
            });
            harness.ecs.reconcileServices();
            assertEquals(1, harness.runningTasks("web").size());
            assertNotEquals(original.getTaskArn(), harness.runningTasks("web").getFirst().getTaskArn());
        }
    }

    @Test
    void retainedNamespaceHelperKeepsTaskStoppingUntilItsPublishedPortIsReleased() {
        try (Harness harness = new Harness()) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
            namespaceStops(harness, lifecycle, credentials);
            AtomicBoolean removalAllowed = new AtomicBoolean();
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                attempts.incrementAndGet();
                if (!removalAllowed.get()) {
                    throw new IllegalStateException("Docker could not remove the namespace helper");
                }
                return null;
            }).when(lifecycle).removeIfExistsStrict("helper");
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();

            harness.forceDeployment("web");
            await().atMost(TIMEOUT).untilAsserted(() -> {
                harness.ecs.reconcileServices();
                assertEquals("STOPPING", original.getLastStatus());
                assertEquals(0, harness.runningTasks("web").size());
                assertTrue(attempts.get() >= 2, "namespace removal remains retryable");
                verify(credentials, atLeastOnce()).revoke(original.getTaskArn());
            });
            verify(harness.manager, times(1)).startTask(any(), any(), any(), anyString());

            removalAllowed.set(true);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                harness.ecs.reconcileServices();
                assertEquals("STOPPED", original.getLastStatus());
            });
            harness.ecs.reconcileServices();
            assertEquals(1, harness.runningTasks("web").size());
        }
    }

    @Test
    void slowHelperRemovalDoesNotBlockAnotherProtectedServiceFromStarting() throws Exception {
        try (Harness harness = new Harness()) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            NamespaceStops namespaces = namespaceStops(harness, lifecycle, mock(EcsTaskRoleCredentials.class));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("namespace removal was never released");
                }
                return null;
            }).when(lifecycle).removeIfExistsStrict("helper");
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            try {
                harness.forceDeployment("web");
                assertTimeoutPreemptively(TIMEOUT, harness.ecs::reconcileServices);
                assertTrue(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                harness.registerTaskDefinition(List.of(new PortMapping(9090, 9090, "tcp")));
                doAnswer(invocation -> {
                    EcsTask task = invocation.getArgument(0);
                    namespaces.register("eni-independent", "independent-helper", "172.18.0.3");
                    return new EcsTaskHandle(task.getTaskArn(), Map.of("app", task.getTaskArn()), Map.of());
                }).when(harness.manager).startTask(any(), any(), any(), anyString());
                harness.createService("independent", Map.of());

                assertTimeoutPreemptively(TIMEOUT, harness.ecs::reconcileServices);

                assertEquals("STOPPING", original.getLastStatus());
                assertEquals(1, harness.runningTasks("independent").size());
            } finally {
                release.countDown();
            }
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
        }
    }

    @Test
    void slowGracefulStopDoesNotBlockAnUnrelatedService() throws Exception {
        try (Harness harness = new Harness()) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                EcsTaskHandle handle = invocation.getArgument(0);
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("graceful stop was never released");
                }
                handle.recordContainerRemoved("app");
                return Map.of("app", 0);
            }).when(harness.manager).stopTaskAndCollectExitCodes(any());
            try {
                harness.forceDeployment("web");
                assertTimeoutPreemptively(TIMEOUT, harness.ecs::reconcileServices);
                assertTrue(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                harness.registerTaskDefinition(List.of(new PortMapping(9090, 9090, "tcp")));
                harness.createService("independent", Map.of());

                assertTimeoutPreemptively(TIMEOUT, harness.ecs::reconcileServices);

                assertEquals("STOPPING", original.getLastStatus());
                assertEquals(0, harness.runningTasks("web").size());
                assertEquals(1, harness.runningTasks("independent").size());
                verify(harness.manager, times(1)).stopTaskAndCollectExitCodes(any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void protectedTaskDelaysStablePortReplacementUntilProtectionIsRemoved() {
        try (Harness harness = new Harness()) {
            harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();
            harness.ecs.updateTaskProtection(CLUSTER, List.of(original.getTaskArn()), true, 60, REGION);

            harness.forceDeployment("web");
            harness.ecs.reconcileServices();
            assertEquals("RUNNING", original.getLastStatus());
            assertEquals(1, harness.runningTasks("web").size());
            verify(harness.manager, times(1)).startTask(any(), any(), any(), anyString());

            harness.ecs.updateTaskProtection(CLUSTER, List.of(original.getTaskArn()), false, null, REGION);
            harness.ecs.reconcileServices();
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
            harness.ecs.reconcileServices();
            assertEquals(1, harness.runningTasks("web").size());
            assertNotEquals(original.getTaskArn(), harness.runningTasks("web").getFirst().getTaskArn());
        }
    }

    private static Stream<Map<String, Object>> replacementFirstMinima() {
        return Stream.of(null, Map.of(), Map.of("minimumHealthyPercent", 1),
                Map.of("minimumHealthyPercent", 50), Map.of("minimumHealthyPercent", 100));
    }

    @ParameterizedTest
    @MethodSource("replacementFirstMinima")
    void positiveAndDefaultMinimaKeepReplacementFirst(Map<String, Object> configuration) {
        try (Harness harness = new Harness()) {
            harness.createService("web", configuration);
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();

            harness.forceDeployment("web");
            harness.ecs.reconcileServices();
            assertEquals("RUNNING", original.getLastStatus());
            assertEquals(2, harness.runningTasks("web").size());

            harness.ecs.reconcileServices();
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
            assertEquals(1, harness.runningTasks("web").size());
        }
    }

    @Test
    void failedDeploymentDoesNotDrainPreviousWorkload() {
        try (Harness harness = new Harness()) {
            EcsServiceModel service = harness.createService("web", Map.of("minimumHealthyPercent", 0));
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();

            harness.forceDeployment("web");
            service.setFailedDeploymentId(service.getDeploymentId());
            harness.ecs.reconcileServices();

            assertEquals("RUNNING", original.getLastStatus());
            assertEquals(1, harness.runningTasks("web").size());
            verify(harness.manager, times(1)).startTask(any(), any(), any(), anyString());
        }
    }

    @Test
    void daemonStillReplacesItsTaskAfterStoppingItWithoutHostPublishing() {
        try (Harness harness = new Harness(PublicationMode.PRIVATE_AWSVPC)) {
            harness.ecs.registerContainerInstance(CLUSTER, null, List.of(), REGION);
            harness.createService("web", Map.of("minimumHealthyPercent", 0), "DAEMON");
            harness.ecs.reconcileServices();
            EcsTask original = harness.runningTasks("web").getFirst();

            harness.forceDeployment("web");
            harness.ecs.reconcileServices();
            assertEquals("STOPPED", original.getDesiredStatus());
            assertEquals(0, harness.runningTasks("web").size());
            await().atMost(TIMEOUT).until(() -> "STOPPED".equals(original.getLastStatus()));
            harness.ecs.reconcileServices();

            EcsTask replacement = harness.runningTasks("web").getFirst();
            assertNotEquals(original.getTaskArn(), replacement.getTaskArn());
            assertEquals(original.getContainerInstanceArn(), replacement.getContainerInstanceArn());
        }
    }

    private static NamespaceStops namespaceStops(Harness harness, ContainerLifecycleManager lifecycle,
                                                  EcsTaskRoleCredentials credentials) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        SecurityGroupFirewallManager firewall = new SecurityGroupFirewallManager(docker, null, lifecycle,
                config, mock(EmbeddedDnsServer.class));
        NamespaceStops namespaces = new NamespaceStops(docker, firewall);
        namespaces.register("eni-web", "helper", "172.18.0.2");
        EcsContainerManager stopping = new EcsContainerManager(null, lifecycle, null, null, config,
                null, null, null, null, null, null, null, null, firewall, credentials, null, null);
        doAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", task.getTaskArn()), Map.of(),
                    null, "eni-web", REGION);
        }).when(harness.manager).startTask(any(), any(), any(), anyString());
        doAnswer(invocation -> stopping.stopTaskAndCollectExitCodes(invocation.getArgument(0)))
                .when(harness.manager).stopTaskAndCollectExitCodes(any());
        return namespaces;
    }

    private record NamespaceStops(DockerClient docker, SecurityGroupFirewallManager firewall) {
        private void register(String eniId, String helperId, String address) {
            when(docker.inspectContainerCmd(helperId).exec().getState().getRunning()).thenReturn(true);
            ContainerExecStubs.completeEveryExec(docker, helperId, 0, "", "");
            SecurityGroup group = new SecurityGroup();
            group.setGroupId("sg-local");
            firewall.register(new SecurityGroupNftCompiler.Endpoint("000000000000", REGION, "vpc-local",
                    eniId, address, address, Set.of("sg-local"), List.of(group)), helperId, Map.of());
        }
    }

    private enum PublicationMode {
        STABLE_AWSVPC, STABLE_AWSVPC_IMPLICIT, DYNAMIC_AWSVPC, PRIVATE_AWSVPC, MOCK,
        BRIDGE_FIXED, BRIDGE_DYNAMIC, HOST_FIXED
    }

    private static final class Harness implements AutoCloseable {
        private final EcsContainerManager manager = mock(EcsContainerManager.class);
        private final NetworkMode networkMode;
        private final EcsService ecs;

        private Harness() {
            this(PublicationMode.STABLE_AWSVPC);
        }

        private Harness(PublicationMode publication) {
            networkMode = switch (publication) {
                case BRIDGE_FIXED, BRIDGE_DYNAMIC -> NetworkMode.bridge;
                case HOST_FIXED -> NetworkMode.host;
                default -> NetworkMode.awsvpc;
            };
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().ecs().mock()).thenReturn(publication == PublicationMode.MOCK);
            when(config.services().ecs().publishAwsvpcPortsToHost())
                    .thenReturn(publication != PublicationMode.PRIVATE_AWSVPC);
            when(config.services().ecs().dynamicAwsvpcHostPorts())
                    .thenReturn(publication == PublicationMode.DYNAMIC_AWSVPC);
            when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
            when(manager.getExitCodeIfStopped(anyString())).thenReturn(null);
            when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
                EcsTask task = invocation.getArgument(0);
                return new EcsTaskHandle(task.getTaskArn(), Map.of("app", task.getTaskArn()), Map.of());
            });
            when(manager.stopTaskAndCollectExitCodes(any())).thenAnswer(invocation -> {
                EcsTaskHandle handle = invocation.getArgument(0);
                handle.recordContainerRemoved("app");
                return Map.of("app", 0);
            });
            ecs = new EcsService(new RegionResolver(REGION, "000000000000"), manager, config,
                    mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory("000000000000"), null);
            ecs.initializeStorage();
            ecs.createCluster(CLUSTER, REGION);
            registerTaskDefinition(publication == PublicationMode.BRIDGE_DYNAMIC
                    || publication == PublicationMode.STABLE_AWSVPC_IMPLICIT
                    ? List.of(new PortMapping(8080)) : List.of(new PortMapping(8080, 8080, "tcp")));
        }

        private TaskDefinition registerTaskDefinition(List<PortMapping> ports) {
            ContainerDefinition container = new ContainerDefinition();
            container.setName("app");
            container.setImage("example:test");
            container.setPortMappings(ports);
            return ecs.registerTaskDefinition("web", List.of(container), networkMode,
                    null, null, null, null, List.of(), REGION);
        }

        private EcsServiceModel createService(String name, Map<String, Object> deployment) {
            return createService(name, deployment, "REPLICA");
        }

        private EcsServiceModel createService(String name, Map<String, Object> deployment, String strategy) {
            AwsVpcConfiguration vpc = new AwsVpcConfiguration();
            vpc.setSubnets(List.of("subnet-local"));
            NetworkConfiguration network = new NetworkConfiguration();
            network.setAwsvpcConfiguration(vpc);
            CreateServiceRequest request = new CreateServiceRequest();
            request.setCluster(CLUSTER);
            request.setServiceName(name);
            request.setTaskDefinition("web");
            request.setDesiredCount(1);
            request.setLaunchType(LaunchType.EC2);
            if (networkMode == NetworkMode.awsvpc) {
                request.setNetworkConfiguration(network);
            }
            request.setDeploymentConfiguration(deployment);
            request.setSchedulingStrategy(strategy);
            return ecs.createService(request, REGION);
        }

        private void forceDeployment(String name) {
            ecs.updateService(CLUSTER, name, null, null, null, null, true, REGION);
        }

        private List<EcsTask> runningTasks(String name) {
            return ecs.describeTasks(CLUSTER, ecs.listTasks(CLUSTER, null, null, null, REGION), REGION).stream()
                    .filter(task -> ("service:" + name).equals(task.getGroup()))
                    .filter(task -> "RUNNING".equals(task.getLastStatus())).toList();
        }

        @Override
        public void close() {
            ecs.stopManagedContainers();
        }
    }
}
