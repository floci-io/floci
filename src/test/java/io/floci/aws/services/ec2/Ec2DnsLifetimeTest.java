package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.services.ec2instanceconnect.InstanceConnectKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.*;

class Ec2DnsLifetimeTest {
    private static final String ADDRESS = "172.17.0.7";
    private static final String CONTAINER = "ec2-dns-workload";

    @ParameterizedTest
    @CsvSource({"false,success", "true,success", "false,absent", "true,absent",
        "false,stopped", "true,stopped", "false,unknown", "true,unknown"})
    void stopRetiresOnlyConfirmedWorkloads(boolean shutdown, String outcome) throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch persisted = new CountDownLatch(1);
            fixture.manager.setPersister(instance -> persisted.countDown());
            doAnswer(invocation -> {
                entered.countDown();
                release.await();
                switch (outcome) {
                    case "absent" -> throw new NotFoundException("already removed");
                    case "stopped" -> throw new NotModifiedException("already stopped");
                    case "unknown" -> throw new IllegalStateException("daemon unavailable");
                    default -> { }
                }
                return null;
            }).when(fixture.stop).exec();
            Future<?> request = executor.submit(() -> {
                if (shutdown) {
                    fixture.manager.stopForShutdown(fixture.instance);
                } else {
                    fixture.manager.stop(fixture.instance);
                }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent(), "graceful stop is still running");
            } finally {
                release.countDown();
            }
            request.get(5, TimeUnit.SECONDS);
            if (!shutdown) {
                assertTrue(persisted.await(5, TimeUnit.SECONDS));
            }
            assertEquals("unknown".equals(outcome), fixture.metadata.vpcForClient(ADDRESS).isPresent());
            assertSame(fixture.instance, fixture.metadata.registeredContainer(ADDRESS).orElseThrow(),
                    "protected IMDS registration has a distinct lifetime");
        }
    }

    @ParameterizedTest
    @CsvSource({"success,false", "absent,false", "unknown,false", "success,true", "absent,true", "none,false"})
    void terminationKeepsOriginUntilRemovalAndRetiresBeforeLaterCleanup(String outcome, boolean reused)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch removing = new CountDownLatch(1);
            CountDownLatch finishRemoval = new CountDownLatch(1);
            CountDownLatch cleaning = new CountDownLatch(1);
            CountDownLatch finishCleanup = new CountDownLatch(1);
            CountDownLatch persisted = new CountDownLatch(1);
            fixture.instance.setSshHostPort(22001);
            fixture.manager.setPersister(instance -> persisted.countDown());
            doAnswer(invocation -> {
                removing.countDown();
                finishRemoval.await();
                switch (outcome) {
                    case "absent" -> throw new NotFoundException("already removed");
                    case "unknown" -> throw new IllegalStateException("daemon unavailable");
                    default -> { }
                }
                return null;
            }).when(fixture.remove).exec();
            doAnswer(invocation -> {
                cleaning.countDown();
                finishCleanup.await();
                return null;
            }).when(fixture.ports).release(22001);
            if ("none".equals(outcome)) {
                fixture.instance.setDockerContainerId(null);
            }
            fixture.manager.terminate(fixture.instance);
            try {
                if (!"none".equals(outcome)) {
                    assertTrue(removing.await(5, TimeUnit.SECONDS));
                    assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent(),
                            "a container still being removed retains its origin");
                }
                if (reused) {
                    Instance replacement = instance("i-replacement");
                    replacement.setVpcId("vpc-other");
                    fixture.metadata.registerContainer(ADDRESS, replacement.getInstanceId(), replacement);
                }
                finishRemoval.countDown();
                assertTrue(cleaning.await(5, TimeUnit.SECONDS));
                assertEquals(reused || "unknown".equals(outcome),
                        fixture.metadata.vpcForClient(ADDRESS).isPresent(),
                        "confirmed removal revokes DNS before remaining cleanup");
            } finally {
                finishRemoval.countDown();
                finishCleanup.countDown();
            }
            assertTrue(persisted.await(5, TimeUnit.SECONDS));
            if (reused) {
                assertEquals("vpc-other", fixture.metadata.vpcForClient(ADDRESS).orElseThrow().vpcId());
            } else if ("unknown".equals(outcome)) {
                assertSame(fixture.instance, fixture.metadata.registeredContainer(ADDRESS).orElseThrow());
                assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent());
                verifyNoInteractions(fixture.networks);
            } else {
                assertTrue(fixture.metadata.registeredContainer(ADDRESS).isEmpty());
                verify(fixture.networks).releasePrivateIp(fixture.instance.getOwnerId(),
                        fixture.instance.getRegion(), fixture.instance.getSubnetId(),
                        fixture.instance.getPrivateIpAddress());
            }
        }
    }

    @Test
    void stoppedInstanceCannotRevokeAnAddressReassignedToAnotherWorkload() {
        try (Fixture fixture = new Fixture()) {
            Instance replacement = instance("i-replacement");
            replacement.setVpcId("vpc-other");
            fixture.metadata.registerContainer(ADDRESS, replacement.getInstanceId(), replacement);
            fixture.manager.stopForShutdown(fixture.instance);
            assertEquals("vpc-other", fixture.metadata.vpcForClient(ADDRESS).orElseThrow().vpcId());
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void restartKeepsDnsInactiveUntilConfirmedRunning(boolean failed) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.manager.stopForShutdown(fixture.instance);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch persisted = new CountDownLatch(1);
            fixture.manager.setPersister(instance -> persisted.countDown());
            doAnswer(invocation -> {
                entered.countDown();
                release.await();
                if (failed) {
                    throw new IllegalStateException("start failed");
                }
                return null;
            }).when(fixture.start).exec();
            fixture.manager.start(fixture.instance);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.metadata.vpcForClient(ADDRESS).isEmpty());
                assertSame(fixture.instance, fixture.metadata.registeredContainer(ADDRESS).orElseThrow());
            } finally {
                release.countDown();
            }
            assertTrue(persisted.await(5, TimeUnit.SECONDS));
            assertEquals(!failed, fixture.metadata.vpcForClient(ADDRESS).isPresent());
        }
    }

    @Test
    void restoreOfRunningWorkloadReactivatesDns() {
        try (Fixture fixture = new Fixture()) {
            fixture.manager.stopForShutdown(fixture.instance);
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isEmpty());
            assertTrue(fixture.manager.restoreMetadataRegistration(fixture.instance));
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent());
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void laterShutdownWinsOverEarlierRunningObservation(boolean restore) throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            fixture.manager.stopForShutdown(fixture.instance);
            CountDownLatch observedRunning = new CountDownLatch(1);
            CountDownLatch inspecting = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            fixture.manager.setPersister(instance -> done.countDown());
            when(fixture.lifecycle.isContainerRunning(CONTAINER)).thenAnswer(invocation -> {
                observedRunning.countDown();
                return true;
            });
            when(fixture.inspect.exec()).thenAnswer(invocation -> {
                if (observedRunning.getCount() == 0) {
                    inspecting.countDown();
                    release.await();
                }
                return fixture.response;
            });
            Future<?> request = executor.submit(() -> {
                if (restore) {
                    fixture.manager.restoreMetadataRegistration(fixture.instance);
                    done.countDown();
                } else {
                    fixture.manager.start(fixture.instance);
                }
            });
            try {
                assertTrue(inspecting.await(5, TimeUnit.SECONDS));
                fixture.manager.stopForShutdown(fixture.instance);
            } finally {
                release.countDown();
            }
            request.get(5, TimeUnit.SECONDS);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isEmpty(),
                    "an old running observation must not reactivate a subsequently stopped workload");
        }
    }

    @Test
    void metadataStopDiscardsOldDnsSuspension() {
        try (Fixture fixture = new Fixture()) {
            fixture.manager.stopForShutdown(fixture.instance);
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isEmpty());
            fixture.metadata.stop();
            fixture.metadata.registerContainer(ADDRESS, fixture.instance.getInstanceId(), fixture.instance);
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent());
        }
    }

    @ParameterizedTest
    @CsvSource({"NOT_RUNNING,true", "RUNNING,false", "UNKNOWN,false"})
    void recoveryProbeRetiresDnsOnlyAfterConfirmedStop(ContainerLiveness liveness, boolean stopped) {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("stop result unavailable")).when(fixture.stop).exec();
            when(fixture.lifecycle.probeContainer(CONTAINER)).thenReturn(liveness);
            assertEquals(stopped, fixture.manager.stopAndConfirm(fixture.instance));
            assertEquals(!stopped, fixture.metadata.vpcForClient(ADDRESS).isPresent());
            assertSame(fixture.instance, fixture.metadata.registeredContainer(ADDRESS).orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({"remove", "replace", "suspend", "unchanged"})
    void dnsLookupRechecksOwnershipAfterReadingInstance(String change) throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Instance observed = spy(instance("i-observed"));
            fixture.metadata.registerContainer(ADDRESS, observed.getInstanceId(), observed);
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                reading.countDown();
                release.await();
                return "123456789012";
            }).when(observed).getOwnerId();
            Future<Optional<ClientVpc>> lookup = executor.submit(() -> fixture.metadata.vpcForClient(ADDRESS));
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                switch (change) {
                    case "remove" -> fixture.metadata.unregisterInstance(observed);
                    case "replace" -> {
                        Instance replacement = instance("i-new-owner");
                        replacement.setVpcId("vpc-new-owner");
                        fixture.metadata.registerContainer(ADDRESS, replacement.getInstanceId(), replacement);
                        fixture.metadata.unregisterInstance(observed);
                        assertEquals("vpc-new-owner", fixture.metadata.vpcForClient(ADDRESS).orElseThrow().vpcId());
                    }
                    case "suspend" -> fixture.metadata.suspendDns(observed);
                    default -> { }
                }
            } finally {
                release.countDown();
            }
            assertEquals("unchanged".equals(change), lookup.get(5, TimeUnit.SECONDS).isPresent(),
                    "a captured instance must not restore revoked or reassigned DNS scope");
        }
    }

    @Test
    void recoveryWithoutContainerRetiresRegisteredOrigin() {
        try (Fixture fixture = new Fixture()) {
            fixture.instance.setDockerContainerId(null);
            assertTrue(fixture.manager.stopAndConfirm(fixture.instance));
            assertTrue(fixture.metadata.vpcForClient(ADDRESS).isEmpty());
            assertSame(fixture.instance, fixture.metadata.registeredContainer(ADDRESS).orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void unregisterInvalidatesOlderResumeWithoutBlockingNewObservation(boolean freshObservation) {
        try (Fixture fixture = new Fixture()) {
            Ec2MetadataServer.DnsSuspension observed = fixture.metadata.suspendDns(fixture.instance);
            fixture.metadata.unregisterInstance(fixture.instance);
            fixture.metadata.registerContainer(ADDRESS, fixture.instance.getInstanceId(), fixture.instance);
            if (freshObservation) {
                observed = fixture.metadata.dnsSuspension(fixture.instance);
            }
            fixture.metadata.resumeDns(fixture.instance, observed);
            assertEquals(freshObservation, fixture.metadata.vpcForClient(ADDRESS).isPresent());
        }
    }

    @ParameterizedTest
    @CsvSource({"success,none", "absent,none", "unknown,none", "success,address", "unknown,address",
        "success,container", "absent,container"})
    void orphanReconciliationRetiresOnlyConfirmedMatchingTransport(String outcome, String reuse) throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch removing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                removing.countDown();
                release.await();
                if ("unknown".equals(outcome)) {
                    throw new IllegalStateException("daemon unavailable");
                }
                if ("absent".equals(outcome)) {
                    throw new NotFoundException("already removed");
                }
                return null;
            }).when(fixture.remove).exec();
            doAnswer(invocation -> {
                fixture.actualLifecycle.removeIfExistsStrict(CONTAINER);
                return null;
            }).when(fixture.lifecycle).removeIfExistsStrict(CONTAINER);
            doAnswer(invocation -> {
                fixture.actualLifecycle.removeIfExists(CONTAINER);
                return null;
            }).when(fixture.lifecycle).removeIfExists(CONTAINER);
            Future<Integer> request = executor.submit(() -> fixture.manager.reconcileOrphanedContainers(
                    (region, instanceId) -> false));
            boolean reused = !"none".equals(reuse);
            Instance replacement = "container".equals(reuse) ? fixture.instance : instance("i-replacement");
            try {
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.metadata.vpcForClient(ADDRESS).isPresent(), "removal is not confirmed yet");
                if (reused) {
                    replacement.setDockerContainerId("replacement-container");
                    fixture.metadata.registerContainer(ADDRESS, replacement.getInstanceId(), replacement);
                }
            } finally {
                release.countDown();
            }
            assertEquals("unknown".equals(outcome) ? 0 : 1, request.get(5, TimeUnit.SECONDS));
            assertEquals(reused || "unknown".equals(outcome), fixture.metadata.vpcForClient(ADDRESS).isPresent());
            assertSame(reused ? replacement : fixture.instance,
                    fixture.metadata.registeredContainer(ADDRESS).orElseThrow());
        }
    }

    private static Instance instance(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setDockerContainerId(CONTAINER);
        instance.setOwnerId("123456789012");
        instance.setRegion("us-east-1");
        instance.setVpcId("vpc-dns");
        instance.setContainerBridgeIp(ADDRESS);
        instance.setState(InstanceState.running());
        return instance;
    }

    private static class Fixture implements AutoCloseable {
        private final ContainerLifecycleManager actualLifecycle;
        private final Ec2MetadataServer metadata = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        private final Instance instance = instance("i-dns");
        private final StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        private final RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        private final PortAllocator ports = mock(PortAllocator.class);
        private final VpcNetworkManager networks = mock(VpcNetworkManager.class);
        private final StartContainerCmd start = mock(StartContainerCmd.class, RETURNS_SELF);
        private final Ec2ContainerManager manager;
        private final InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        private final InspectContainerResponse response = mock(InspectContainerResponse.class);
        private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);

        Fixture() {
            DockerClient docker = mock(DockerClient.class);
            ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
            Container container = mock(Container.class);
            when(container.getId()).thenReturn(CONTAINER);
            when(container.getLabels()).thenReturn(Map.of(
                    Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE,
                    Ec2ContainerManager.LABEL_OWNER, "4566",
                    Ec2ContainerManager.LABEL_REGION, "us-east-1",
                    Ec2ContainerManager.LABEL_RESOURCE_ID, "i-dns"));
            when(docker.listContainersCmd()).thenReturn(listing);
            when(listing.exec()).thenReturn(List.of(container));
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            actualLifecycle = new ContainerLifecycleManager(docker, docker, null, null, config);
            when(config.port()).thenReturn(4566);
            when(config.services().ec2().reconcileContainersOnStartup()).thenReturn(true);
            when(docker.removeContainerCmd(CONTAINER)).thenReturn(remove);
            when(docker.stopContainerCmd(CONTAINER)).thenReturn(stop);
            when(docker.startContainerCmd(CONTAINER)).thenReturn(start);
            when(docker.inspectContainerCmd(CONTAINER)).thenReturn(inspect);
            NetworkSettings networks = mock(NetworkSettings.class);
            when(response.getNetworkSettings()).thenReturn(networks);
            when(networks.getNetworks()).thenReturn(Map.of("bridge", new ContainerNetwork().withIpv4Address(ADDRESS)));
            when(inspect.exec()).thenReturn(response);
            when(lifecycle.isContainerRunning(CONTAINER)).thenReturn(true);
            manager = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                    docker, ports, config, metadata,
                    mock(Ec2PortForwardManager.class), mock(RegionResolver.class),
                    mock(ContainerNetworkReachability.class), this.networks,
                    mock(ContainerReachableEndpoint.class));
            metadata.registerContainer(ADDRESS, instance.getInstanceId(), instance);
        }

        @Override
        public void close() {
            manager.stop();
        }
    }
}
