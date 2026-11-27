package io.floci.aws.services.rds;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.VpcRelayFixture;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.rds.container.RdsContainerHandle;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.model.BlueGreenDeployment;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbClusterEndpoint;
import io.floci.aws.services.rds.model.DbEndpoint;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.DbInstanceSettings;
import io.floci.aws.services.rds.model.DbInstanceStatus;
import io.floci.aws.services.rds.model.ReadReplicaRequest;
import io.floci.aws.services.rds.proxy.RdsProxyBinding;
import io.floci.aws.services.rds.proxy.RdsProxyManager;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * With {@code endpoints-in-vpc} on, a standalone instance serves the Port it was created with at its own address in
 * its subnet, under the name RDS gives it, through every step of its life, and a cluster's instances serve the
 * cluster's port the same way, its endpoint names resolving to them; the relay is a real
 * {@link io.floci.aws.services.ec2.VpcAddressRelays} over a mocked EC2 and Docker.
 */
@ResourceLock(ParallelLocks.PORTS)
class RdsEndpointsInVpcTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String SUBNET_A = Ec2Service.defaultSubnetId(REGION, "a", "000000000000");
    private static final String SUBNET_B = Ec2Service.defaultSubnetId(REGION, "b", "000000000000");
    private static final String SUBNET_C = "subnet-0c0c0c0c0c0c0c0c0";
    private static final String SUBNET_D = "subnet-0d0d0d0d0d0d0d0d0";
    private static final String SUBNET_E = "subnet-0e0e0e0e0e0e0e0e0";
    private static final String SUBNET_F = "subnet-0f0f0f0f0f0f0f0f0";

    private EmulatorConfig config;
    private RdsContainerManager containerManager;
    private RdsProxyManager proxyManager;
    private Ec2Service ec2Service;
    private VpcRelayFixture fixture;
    private DockerHostResolver hostResolver;
    private RdsEndpointAddresses endpointAddresses;
    private StorageBackend<String, DbInstance> instances;
    private StorageBackend<String, DbCluster> clusters;
    private RdsService rdsService;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        EmulatorConfig.RdsServiceConfig rds = config.services().rds();
        when(rds.proxyBasePort()).thenReturn(7000);
        when(rds.proxyMaxPort()).thenReturn(7099);
        when(rds.iamTokenEndpointBinding()).thenReturn(true);
        when(rds.mock()).thenReturn(false);
        when(rds.endpointsInVpc()).thenReturn(true);
        when(rds.defaultSqlServerImage()).thenReturn("mcr.microsoft.com/mssql/server:2022-latest");
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);

        containerManager = mock(RdsContainerManager.class);
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "10.9.0.5", 5432));
        proxyManager = mock(RdsProxyManager.class);
        ec2Service = mock(Ec2Service.class);
        when(ec2Service.resolveDefaultVpcId(any())).thenReturn("vpc-default");
        when(ec2Service.describeSubnets(any(), anyList(), any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            // The default VPC's subnets are a and b; c and d are only reached by naming them.
            List<Subnet> defaults = List.of(subnet(SUBNET_A, "us-east-1a"), subnet(SUBNET_B, "us-east-1b"));
            List<Subnet> all = List.of(defaults.get(0), defaults.get(1), subnet(SUBNET_C, "us-east-1c"),
                    subnet(SUBNET_D, "us-east-1d"), subnet(SUBNET_E, "us-east-1a", "vpc-other"),
                    subnet(SUBNET_F, "us-east-1b", "vpc-other"));
            return ids == null || ids.isEmpty() ? defaults
                    : all.stream().filter(subnet -> ids.contains(subnet.getSubnetId())).toList();
        });
        fixture = new VpcRelayFixture(ec2Service);
        hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("host.docker.internal");
        when(hostResolver.resolveOn(any())).thenReturn("host.docker.internal");
        endpointAddresses = newEndpointAddresses();
        instances = new FailingStore();
        clusters = new InMemoryStorage<>();
        rdsService = newService(instances);
    }

    @Test
    void anInstanceReportsItsRdsNameAndPortAndServesThemAtItsOwnAddress() {
        DbInstance instance = create("app", "postgres", 5432);

        String name = AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION);
        assertEquals(new DbEndpoint(name, 5432), instance.getEndpoint());
        assertEquals("10.0.1.20", instance.getVpcEndpointAddress());
        assertEquals(instance.getAvailabilityZone(),
                instance.getSubnetAvailabilityZones().get(instance.getVpcEndpointSubnetId()));
        assertEquals(5432, rdsService.connectablePort(instance));
        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:" + instance.getProxyPort() + ",nodelay"));
        ArgumentCaptor<RdsProxyBinding> binding = ArgumentCaptor.forClass(RdsProxyBinding.class);
        verify(proxyManager).startProxy(any(), any(), any(), anyBoolean(), eq(instance.getProxyPort()), any(),
                anyInt(), eq(name), any(), any(), any(), any(), binding.capture());
        assertEquals(name, binding.getValue().advertisedHost());
        assertEquals(5432, binding.getValue().publishedPort());
        assertEquals(Optional.of("10.0.1.20"), endpointAddresses.addressOf(name.toUpperCase()));
    }

    @Test
    void anEndpointInItsVpcIsConnectableOnThePortItReportsWhateverAdvertiseRequestedPortSays() {
        when(config.services().rds().advertiseRequestedPort()).thenReturn(true);

        DbInstance instance = create("app", "postgres", 5432);

        assertEquals(5432, rdsService.connectablePort(instance));
        assertEquals(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION), instance.getEndpoint().address());
    }

    @Test
    void twoInstancesBothServe5432EachAtItsOwnAddress() {
        DbInstance first = create("first", "postgres", 5432);
        DbInstance second = create("second", "postgres", 5432);

        assertEquals(5432, first.getEndpoint().port());
        assertEquals(5432, second.getEndpoint().port());
        assertNotEquals(first.getEndpoint().address(), second.getEndpoint().address());
        assertNotEquals(first.getVpcEndpointAddress(), second.getVpcEndpointAddress());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());
    }

    @Test
    void anInstanceCreatedWithoutAPortServesItsEnginesDefault() {
        assertEquals(3306, create("mysql-db", "mysql", null).getEndpoint().port());
        assertEquals(5432, create("pg-db", "postgres", null).getEndpoint().port());
    }

    @Test
    void aCreateTheSubnetHasNoAddressForFailsAndLeavesNothingBehind() {
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> create("app", "postgres", 5432));

        assertThrows(AwsException.class, () -> rdsService.getDbInstance("app"));
        verify(proxyManager).stopProxy(any());
        assertTrue(fixture.present.isEmpty());
    }

    @Test
    void aStoppedInstanceKeepsItsNameAndIsServedAgainAtItsAddressWhenStarted() {
        DbInstance instance = create("app", "postgres", 5432);
        DbEndpoint endpoint = instance.getEndpoint();

        rdsService.stopDbInstance("app", null, REGION);
        DbInstance stopped = rdsService.getDbInstance("app");
        assertEquals(endpoint, stopped.getEndpoint());
        assertFalse(endpointAddresses.serving(stopped));
        assertTrue(fixture.present.isEmpty());
        assertTrue(endpointAddresses.names(endpoint.address()));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(endpoint.address()));

        rdsService.startDbInstance("app", REGION);
        DbInstance started = rdsService.getDbInstance("app");
        assertEquals(endpoint, started.getEndpoint());
        assertEquals("10.0.1.20", started.getVpcEndpointAddress());
        assertTrue(endpointAddresses.serving(started));
    }

    @Test
    void aDeletedInstanceGivesItsAddressBack() {
        String subnetId = create("app", "postgres", 5432).getVpcEndpointSubnetId();
        assertTrue(endpointAddresses.hasSubnetDependency(REGION, subnetId));

        rdsService.deleteDbInstance("app", REGION);

        assertTrue(fixture.present.isEmpty());
        assertFalse(fixture.leased.contains("10.0.1.20"));
        assertFalse(endpointAddresses.hasSubnetDependency(REGION, subnetId));
        assertFalse(endpointAddresses.names(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION)));
    }

    @Test
    void aDeleteWhoseRelayDockerDoesNotRemoveKeepsTheRecordAndItsPortUntilARetryRemovesIt() {
        DbInstance instance = create("app", "postgres", 5432);
        int proxyPort = instance.getProxyPort();
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");

        assertThrows(IllegalStateException.class, () -> rdsService.deleteDbInstance("app", REGION));

        assertEquals(DbInstanceStatus.DELETING, rdsService.getDbInstance("app").getStatus());
        assertTrue(endpointAddresses.serving(instance));
        assertNotEquals(proxyPort, create("other", "postgres", 5432).getProxyPort());

        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(fixture.lifecycleManager).removeIfExistsStrict("relay-1");
        rdsService.deleteDbInstance("app", REGION);

        assertThrows(AwsException.class, () -> rdsService.getDbInstance("app"));
        assertFalse(endpointAddresses.serving(instance));
        assertFalse(fixture.leased.contains("10.0.1.20"));
    }

    @Test
    void aStopWhoseRelayDockerDoesNotRemoveStillStopsAndTheNextStartServesAgain() {
        DbInstance instance = create("app", "postgres", 5432);
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");

        rdsService.stopDbInstance("app", null, REGION);

        assertEquals(DbInstanceStatus.STOPPED, rdsService.getDbInstance("app").getStatus());
        rdsService.startDbInstance("app", REGION);
        assertEquals(DbInstanceStatus.AVAILABLE, rdsService.getDbInstance("app").getStatus());
        assertTrue(endpointAddresses.serving(instance));
    }

    @Test
    void aMoveWhoseOldRelayCannotBeRemovedKeepsItWithTheInstanceUntilADeleteRemovesIt() {
        DbInstance instance = create("app", "postgres", 5432);
        String oldRelayId = instance.getVpcEndpointId();
        int proxyPort = instance.getProxyPort();
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");

        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);

        DbInstance moved = rdsService.getDbInstance("app");
        assertEquals(List.of(oldRelayId), moved.getStaleVpcEndpointIds());
        assertTrue(List.of(SUBNET_C, SUBNET_D).contains(moved.getVpcEndpointSubnetId()));
        assertThrows(IllegalStateException.class, () -> rdsService.deleteDbInstance("app", REGION));
        assertEquals(DbInstanceStatus.DELETING, rdsService.getDbInstance("app").getStatus());
        assertNotEquals(proxyPort, create("other", "postgres", 5432).getProxyPort());

        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(fixture.lifecycleManager).removeIfExistsStrict("relay-1");
        rdsService.deleteDbInstance("app", REGION);

        assertThrows(AwsException.class, () -> rdsService.getDbInstance("app"));
        assertFalse(fixture.present.contains("relay-1"));
    }

    @Test
    void aFailedMoveWhoseNewRelayMayHaveStartedKeepsItWithTheInstance() {
        DbInstance instance = create("app", "postgres", 5432);
        String oldAddress = instance.getVpcEndpointAddress();
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("relay-x");
            fixture.present.add("relay-x");
            throw new IllegalStateException("start reply lost");
        }).when(fixture.lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-x");

        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);

        DbInstance kept = rdsService.getDbInstance("app");
        assertEquals(oldAddress, kept.getVpcEndpointAddress());
        assertEquals(1, kept.getStaleVpcEndpointIds().size());
        assertTrue(endpointAddresses.serving(kept));
    }

    @Test
    void aRestartRemovesTheRelaysAnInstanceMovedOff() {
        create("app", "postgres", 5432);
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);
        assertEquals(1, rdsService.getDbInstance("app").getStaleVpcEndpointIds().size());
        // Stopped, it is not served at the restart: the restart itself removes the relay.
        rdsService.stopDbInstance("app", null, REGION);
        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(fixture.lifecycleManager).removeIfExistsStrict("relay-1");

        newService(instances, newEndpointAddresses()).restorePersistedRuntime();

        assertEquals(List.of(), newServiceView().getDbInstance("app").getStaleVpcEndpointIds());
    }

    @Test
    void aRelayAMoveLeftThatARestartCannotRemoveKeepsItsOldSubnetInUse() {
        DbInstance instance = create("app", "postgres", 5432);
        String oldSubnet = instance.getVpcEndpointSubnetId();
        String oldRelayName = ContainerStorageHelper.dockerName(fixture.config,
                RdsEndpointAddresses.SERVICE + "-" + instance.getVpcEndpointId());
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);
        rdsService.stopDbInstance("app", null, REGION);
        // The process ends: its leases go with it, and the relay Docker would not remove is left running.
        fixture.leased.clear();
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict(oldRelayName);
        RdsEndpointAddresses restarted = newEndpointAddresses();

        newService(instances, restarted).restorePersistedRuntime();

        assertTrue(restarted.hasSubnetDependency(REGION, oldSubnet));
        assertTrue(fixture.leased.contains("10.0.1.20"));
        assertEquals(1, newServiceView().getDbInstance("app").getStaleVpcEndpointIds().size());
    }

    @Test
    void theNextStartRemovesTheRelaysAnInstanceMovedOff() {
        create("app", "postgres", 5432);
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);
        rdsService.stopDbInstance("app", null, REGION);
        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(fixture.lifecycleManager).removeIfExistsStrict("relay-1");

        rdsService.startDbInstance("app", REGION);

        assertEquals(List.of(), rdsService.getDbInstance("app").getStaleVpcEndpointIds());
        assertFalse(fixture.present.contains("relay-1"));
    }

    @Test
    void aFailedCreateWhoseRelayDockerDoesNotRemoveKeepsTheRecordAndItsPort() {
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        ((FailingStore) instances).failNextAvailableWrite = true;

        assertThrows(IllegalStateException.class, () -> create("app", "postgres", 5432));

        DbInstance kept = rdsService.getDbInstance("app");
        assertTrue(endpointAddresses.serving(kept));
        assertNotEquals(0, kept.getProxyPort(), "the record keeps the port its relay forwards to");
        assertNotEquals(kept.getProxyPort(), create("other", "postgres", 5432).getProxyPort());
    }

    @Test
    void anInstanceWhoseRelayCannotBeRemovedAfterTheSettingIsTurnedOffIsNotServed() {
        DbInstance instance = create("app", "postgres", 5432);
        // Floci ends without removing the relay, which stays written down.
        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        String relayName = ContainerStorageHelper.dockerName(fixture.config,
                RdsEndpointAddresses.SERVICE + "-" + instance.getVpcEndpointId());
        // Taking the relay up and taking it out of the VPC fail; a relay started after them would start.
        doThrow(new IllegalStateException("daemon down")).doThrow(new IllegalStateException("daemon down"))
                .doNothing().when(fixture.lifecycleManager).removeIfExistsStrict(relayName);
        RdsEndpointAddresses restarted = newEndpointAddresses();
        clearInvocations(fixture.lifecycleManager);

        newService(instances, restarted).restorePersistedRuntime();

        DbInstance restored = newServiceView().getDbInstance("app");
        assertTrue(RdsEndpointAddresses.inVpc(restored));
        // The relay left behind is held with its address, and no new one starts while the setting is off.
        verify(fixture.lifecycleManager, never()).createAndStart(any(ContainerSpec.class), any(Consumer.class));
    }

    @Test
    void aRestartRemovesTheRelayAnEarlierFlociLeftRunning() {
        DbInstance instance = create("app", "postgres", 5432);
        String relayName = ContainerStorageHelper.dockerName(fixture.config,
                RdsEndpointAddresses.SERVICE + "-" + instance.getVpcEndpointId());
        clearInvocations(fixture.lifecycleManager);

        newService(instances, newEndpointAddresses()).restorePersistedRuntime();

        verify(fixture.lifecycleManager, atLeastOnce()).removeIfExistsStrict(relayName);
    }

    @Test
    void aRelayARestartCannotRemoveKeepsItsAddressAndSubnetUntilTheNextStartRemovesIt() {
        DbInstance instance = create("app", "postgres", 5432);
        String subnetId = instance.getVpcEndpointSubnetId();
        String relayName = ContainerStorageHelper.dockerName(fixture.config,
                RdsEndpointAddresses.SERVICE + "-" + instance.getVpcEndpointId());
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        rdsService.stopDbInstance("app", null, REGION);
        // The process ends: its leases go with it, and the relay Docker would not remove is left running.
        fixture.leased.clear();
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict(relayName);
        RdsEndpointAddresses restarted = newEndpointAddresses();
        RdsService restartedService = newService(instances, restarted);

        restartedService.restorePersistedRuntime();

        assertTrue(restarted.hasSubnetDependency(REGION, subnetId));
        assertTrue(fixture.leased.contains("10.0.1.20"));

        doNothing().when(fixture.lifecycleManager).removeIfExistsStrict(relayName);
        restartedService.startDbInstance("app", REGION);

        DbInstance started = restartedService.getDbInstance("app");
        assertEquals("10.0.1.20", started.getVpcEndpointAddress());
        assertTrue(restarted.serving(started));
    }

    @Test
    void aPortARelayMayStillForwardToIsNotGivenToAnotherInstance() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        DbInstance instance = create("app", "postgres", 5432);
        instance.setEndpoint(null);
        instances.put("us-east-1::app", instance);
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "10.9.0.5", 5432));
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("relay-x");
            fixture.present.add("relay-x");
            throw new IllegalStateException("start reply lost");
        }).when(fixture.lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-x");

        assertThrows(IllegalStateException.class, () -> rdsService.ensureInstanceBackend("app", REGION));
        fixture.startsRelaysNormally();

        int relayTarget = fixture.placements.all().getFirst().relay().targetPort();
        for (int i = 0; i < 3; i++) {
            assertNotEquals(relayTarget, create("other-" + i, "postgres", 5432).getProxyPort());
        }
    }

    @Test
    void aRestartKnowsAStoppedInstancesNameWithNoAddress() {
        create("app", "postgres", 5432);
        rdsService.stopDbInstance("app", null, REGION);
        RdsEndpointAddresses restarted = newEndpointAddresses();

        newService(instances, restarted).restorePersistedRuntime();

        String name = AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION);
        assertTrue(restarted.names(name));
        assertEquals(Optional.empty(), restarted.addressOf(name));
    }

    @Test
    void aRestartServesTheInstanceAgainAtTheAddressItHeld() {
        DbInstance instance = create("app", "postgres", 5432);
        endpointAddresses.stopManagedContainers();
        assertFalse(fixture.leased.contains("10.0.1.20"));

        newService(instances).restorePersistedRuntime();

        DbInstance restored = newServiceView().getDbInstance("app");
        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION), 5432),
                restored.getEndpoint());
        assertEquals("10.0.1.20", restored.getVpcEndpointAddress());
        assertTrue(endpointAddresses.serving(restored));
    }

    @Test
    void turningTheSettingOffAcrossARestartPutsTheEndpointBackOnFloci() {
        create("app", "postgres", 5432);
        endpointAddresses.stopManagedContainers();
        when(config.services().rds().endpointsInVpc()).thenReturn(false);

        newService(instances).restorePersistedRuntime();

        DbInstance restored = newServiceView().getDbInstance("app");
        assertFalse(RdsEndpointAddresses.inVpc(restored));
        assertNull(restored.getVpcEndpointAddress());
        assertEquals(restored.getProxyPort(), restored.getEndpoint().port());
        assertFalse(restored.getEndpoint().address().endsWith(".rds.amazonaws.com"));
    }

    @Test
    void turningTheSettingOnAcrossARestartPutsAnExistingInstanceInItsVpc() {
        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        DbInstance before = create("app", "postgres", 5432);
        assertFalse(RdsEndpointAddresses.inVpc(before));
        when(config.services().rds().endpointsInVpc()).thenReturn(true);

        newService(instances).restorePersistedRuntime();

        DbInstance restored = newServiceView().getDbInstance("app");
        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION), 5432),
                restored.getEndpoint());
        assertTrue(endpointAddresses.serving(restored));
    }

    @Test
    void anInstanceCreatedWithoutDockerIsServedOnceItsBackendStarts() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        DbInstance instance = create("app", "postgres", 5432);
        assertFalse(endpointAddresses.serving(instance));
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "10.9.0.5", 5432));

        rdsService.ensureInstanceBackend("app", REGION);

        assertTrue(endpointAddresses.serving(rdsService.getDbInstance("app")));
    }

    @Test
    void anInstanceMovedToAnotherSubnetGroupIsServedInOneOfItsSubnets() {
        DbInstance instance = create("app", "postgres", 5432);
        String before = instance.getVpcEndpointAddress();
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));

        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);

        DbInstance moved = rdsService.getDbInstance("app");
        assertTrue(List.of(SUBNET_C, SUBNET_D).contains(moved.getVpcEndpointSubnetId()));
        assertNotEquals(before, moved.getVpcEndpointAddress());
        assertFalse(fixture.leased.contains(before));
        assertFalse(fixture.present.contains("relay-1"), "the relay in the old subnet goes once the new one serves");
        assertTrue(endpointAddresses.serving(moved));
        assertTrue(endpointAddresses.hasSubnetDependency(REGION, moved.getVpcEndpointSubnetId()));
        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION), 5432),
                moved.getEndpoint());
    }

    @Test
    void aCreateWhoseRecordCannotBeWrittenTakesItsRelayAway() {
        ((FailingStore) instances).failNextAvailableWrite = true;

        assertThrows(IllegalStateException.class, () -> create("app", "postgres", 5432));

        assertTrue(fixture.present.isEmpty());
        assertTrue(fixture.leased.isEmpty());
    }

    @Test
    void aRestoreThatFailsAfterServingTakesTheRelayAway() {
        create("app", "postgres", 5432);
        endpointAddresses.stopManagedContainers();
        ((FailingStore) instances).failNextAvailableWrite = true;

        newService(instances).restorePersistedRuntime();

        assertTrue(fixture.present.isEmpty());
        assertTrue(fixture.leased.isEmpty());
    }

    @Test
    void aBackendStartedOnRetryForARecordWithoutAnEndpointReportsItsName() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        DbInstance instance = create("app", "postgres", 5432);
        instance.setEndpoint(null);
        instances.put("us-east-1::app", instance);
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "10.9.0.5", 5432));

        rdsService.ensureInstanceBackend("app", REGION);

        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("app", ACCOUNT, REGION), 5432),
                rdsService.getDbInstance("app").getEndpoint());
    }

    @Test
    void aStoppedInstanceTakenOutOfItsVpcAcrossARestartReportsTheProxyEndpoint() {
        create("app", "postgres", 5432);
        rdsService.stopDbInstance("app", null, REGION);
        when(config.services().rds().endpointsInVpc()).thenReturn(false);

        newService(instances).restorePersistedRuntime();

        DbInstance restored = newServiceView().getDbInstance("app");
        assertFalse(RdsEndpointAddresses.inVpc(restored));
        assertEquals(restored.getProxyPort(), restored.getEndpoint().port());
        assertFalse(restored.getEndpoint().address().endsWith(".rds.amazonaws.com"));
    }

    @Test
    void aStartThatLeavesAPortDsqlNowHoldsKeepsTheNameAndRelaysToTheNewProxyPort() {
        DbInstance instance = create("app", "postgres", 5432);
        int oldPort = instance.getProxyPort();
        DbEndpoint endpoint = instance.getEndpoint();
        rdsService.stopDbInstance("app", null, REGION);
        EmulatorConfig.DsqlServiceConfig dsql = config.services().dsql();
        when(dsql.enabled()).thenReturn(true);
        when(dsql.mock()).thenReturn(false);
        when(dsql.port()).thenReturn(oldPort);

        rdsService.startDbInstance("app", REGION);

        DbInstance started = rdsService.getDbInstance("app");
        assertNotEquals(oldPort, started.getProxyPort());
        assertEquals(endpoint, started.getEndpoint());
        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:" + started.getProxyPort() + ",nodelay"));
    }

    @Test
    void aMoveToASubnetWithNoFreeAddressKeepsTheOldEndpointUntilARestartMovesIt() {
        DbInstance instance = create("app", "postgres", 5432);
        String oldSubnet = instance.getVpcEndpointSubnetId();
        String name = instance.getEndpoint().address();
        rdsService.createDbSubnetGroup("moved", "moved", List.of(SUBNET_C, SUBNET_D));
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), eq(SUBNET_C));
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), eq(SUBNET_D));

        rdsService.modifyDbInstance("app", null, null, "moved", null, REGION);

        DbInstance kept = rdsService.getDbInstance("app");
        assertEquals(oldSubnet, kept.getVpcEndpointSubnetId());
        assertEquals(Optional.of("10.0.1.20"), endpointAddresses.addressOf(name));
        assertTrue(fixture.present.contains("relay-1"));

        endpointAddresses.stopManagedContainers();
        doReturn(Optional.of("10.0.1.40")).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(),
                eq(SUBNET_C));
        doReturn(Optional.of("10.0.1.41")).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(),
                eq(SUBNET_D));
        RdsEndpointAddresses restarted = newEndpointAddresses();
        newService(instances, restarted).restorePersistedRuntime();

        DbInstance moved = newServiceView().getDbInstance("app");
        assertTrue(List.of(SUBNET_C, SUBNET_D).contains(moved.getVpcEndpointSubnetId()));
        assertTrue(restarted.serving(moved));
    }

    @Test
    void aMoveToAnotherVpcThatCouldNotBeServedIsMadeByTheNextStart() {
        DbInstance instance = create("app", "postgres", 5432);
        String oldSubnet = instance.getVpcEndpointSubnetId();
        rdsService.createDbSubnetGroup("other", "other", List.of(SUBNET_E, SUBNET_F));
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), eq(SUBNET_E));
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), eq(SUBNET_F));

        rdsService.modifyDbInstance("app", null, null, "other", null, REGION);

        DbInstance kept = rdsService.getDbInstance("app");
        assertEquals("vpc-other", kept.getVpcId());
        assertEquals(oldSubnet, kept.getVpcEndpointSubnetId());
        assertTrue(endpointAddresses.serving(kept));

        rdsService.stopDbInstance("app", null, REGION);
        doReturn(Optional.of("10.0.2.40")).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(),
                eq(SUBNET_E));
        doReturn(Optional.of("10.0.2.41")).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(),
                eq(SUBNET_F));
        rdsService.startDbInstance("app", REGION);

        DbInstance started = rdsService.getDbInstance("app");
        assertEquals(DbInstanceStatus.AVAILABLE, started.getStatus());
        assertTrue(List.of(SUBNET_E, SUBNET_F).contains(started.getVpcEndpointSubnetId()));
        assertTrue(endpointAddresses.serving(started));
    }

    @Test
    void aFailedCreateWhoseRelayMayHaveStartedAndCouldNotBeRemovedKeepsTheRecordAndItsPort() {
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("relay-x");
            fixture.present.add("relay-x");
            throw new IllegalStateException("start reply lost");
        }).when(fixture.lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-x");

        assertThrows(IllegalStateException.class, () -> create("app", "postgres", 5432));

        DbInstance kept = rdsService.getDbInstance("app");
        assertTrue(endpointAddresses.serving(kept));
        assertNotEquals(0, kept.getProxyPort(), "the record keeps the port the relay may forward to");
    }

    @Test
    void twoInstancesAskingForTheSamePortInsideTheProxyRangeBothGetIt() {
        DbInstance first = create("first", "postgres", 7010);
        DbInstance second = create("second", "postgres", 7010);

        assertEquals(7010, first.getEndpoint().port());
        assertEquals(7010, second.getEndpoint().port());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());
    }

    @Test
    void aPortOutsideRdsRangeIsStillRefused() {
        AwsException refused = assertThrows(AwsException.class, () -> create("app", "postgres", 80));

        assertEquals("InvalidParameterValue", refused.getErrorCode());
    }

    @Test
    void aReadReplicaServesItsSourcesPort() {
        create("source", "postgres", 6543);

        DbInstance replica = rdsService.createDbInstanceReadReplica(new ReadReplicaRequest("replica", "source",
                null, null, null, null, null, null, null, null, null, null, null, null, null, Map.of()), null);

        assertEquals(6543, replica.getEndpoint().port());
    }

    @Test
    void aRestoreFromASnapshotServesTheOriginalInstancesPort() {
        when(containerManager.createPostgresSnapshot(any(), any())).thenReturn("-- dump");
        create("source", "postgres", 6543);
        rdsService.createDbSnapshot("snap", "source");

        DbInstance restored = rdsService.restoreDbInstanceFromDbSnapshot("restored", "snap",
                null, null, false, null, List.of(), Map.of(), REGION);

        assertEquals(6543, restored.getEndpoint().port());
    }

    @Test
    void aGreenInstanceServesItsBluesPort() {
        DbInstance blue = rdsService.createDbInstance("bg-blue", "postgres", "16.3", "admin", "password",
                "dbname", "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 1, null, null, null), null, 6543);

        BlueGreenDeployment deployment = rdsService.createBlueGreenDeployment(
                new RdsService.BlueGreenDeploymentRequest("bg", blue.getDbInstanceArn(), "17.4", null, null, null,
                        null, null, Map.of()), REGION);

        String greenArn = deployment.getTarget();
        DbInstance green = rdsService.getDbInstance(greenArn.substring(greenArn.lastIndexOf(':') + 1));
        assertEquals(6543, green.getEndpoint().port());
    }

    @Test
    void aCreateWhoseContainerFailsWithDockerDownLeavesNoRecordBehind() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("daemon down"));
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict(anyString());

        assertThrows(IllegalStateException.class, () -> create("app", "postgres", 5432));

        assertThrows(AwsException.class, () -> rdsService.getDbInstance("app"));
    }

    @Test
    void aStopOfAnInstanceNeverServedAsksDockerNothingAboutRelays() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        create("app", "postgres", 5432);
        clearInvocations(fixture.lifecycleManager);

        rdsService.stopDbInstance("app", null, REGION);

        verify(fixture.lifecycleManager, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void anInstanceNotServedYetIsKnownByNameWithNoAddress() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        DbInstance instance = create("app", "postgres", 5432);

        assertTrue(endpointAddresses.names(instance.getEndpoint().address()));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(instance.getEndpoint().address()));
    }

    @Test
    void aRestartKnowsTheNameOfAnInstanceItCouldNotServe() {
        DbInstance instance = create("app", "postgres", 5432);
        endpointAddresses.stopManagedContainers();
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), anyString());
        doReturn(false).when(ec2Service).reserveVpcPrivateIp(anyString(), anyString(), anyString(), anyString());
        RdsEndpointAddresses restarted = newEndpointAddresses();

        newService(instances, restarted).restorePersistedRuntime();

        assertTrue(restarted.names(instance.getEndpoint().address()));
        assertFalse(restarted.serving(newServiceView().getDbInstance("app")));
    }

    @Test
    void aStartWhoseRelayCannotBeServedLeavesTheInstanceStoppedForARetry() {
        create("app", "postgres", 5432);
        rdsService.stopDbInstance("app", null, REGION);
        doReturn(Optional.empty()).when(ec2Service).allocateVpcPrivateIp(anyString(), anyString(), anyString());
        doReturn(false).when(ec2Service).reserveVpcPrivateIp(anyString(), anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> rdsService.startDbInstance("app", REGION));

        assertEquals(DbInstanceStatus.STOPPED, rdsService.getDbInstance("app").getStatus());
    }

    @Test
    void aRestartKeepsTheProxyPortOfAnInstanceWhoseRelayItCouldNotRemove() {
        DbInstance instance = create("app", "postgres", 5432);
        int proxyPort = instance.getProxyPort();
        String relayName = ContainerStorageHelper.dockerName(fixture.config,
                RdsEndpointAddresses.SERVICE + "-" + instance.getVpcEndpointId());
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict(relayName);

        newService(instances, newEndpointAddresses()).restorePersistedRuntime();

        assertEquals(proxyPort, newServiceView().getDbInstance("app").getProxyPort());
    }

    @Test
    void aResetWritesDownTheRelaysItCouldNotRemoveAndForgetsTheNames() {
        DbInstance instance = create("app", "postgres", 5432);
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");
        endpointAddresses.stopManagedContainers();
        fixture.placements.all().forEach(fixture.placements::remove);
        endpointAddresses.clear();

        endpointAddresses.afterReset();

        assertEquals(1, fixture.placements.all().size());
        assertTrue(endpointAddresses.targetsPort(instance.getProxyPort()));
        assertFalse(endpointAddresses.names(instance.getEndpoint().address()));
    }

    @Test
    void mockModeKeepsTheProxyEndpoint() {
        when(config.services().rds().mock()).thenReturn(true);

        DbInstance instance = create("app", "postgres", 5432);

        assertFalse(RdsEndpointAddresses.inVpc(instance));
        assertTrue(fixture.present.isEmpty());
    }

    @Test
    void aSwitchoverHandsTheBlueNameAndAddressToGreen() {
        DbInstance blue = rdsService.createDbInstance("bg-blue", "postgres", "16.3", "admin", "password",
                "dbname", "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                Map.of(), List.of(), null, null, true,
                new DbInstanceSettings(null, null, 1, null, null, null), null, 5432);
        String blueAddress = blue.getVpcEndpointAddress();
        BlueGreenDeployment deployment = rdsService.createBlueGreenDeployment(
                new RdsService.BlueGreenDeploymentRequest("bg", blue.getDbInstanceArn(), "17.4", null, null, null,
                        null, null, Map.of()), REGION);

        String greenArn = deployment.getTarget();
        String greenId = greenArn.substring(greenArn.lastIndexOf(':') + 1);
        assertTrue(endpointAddresses.names(AwsEndpoints.rdsInstanceHost(greenId, ACCOUNT, REGION)));

        rdsService.switchoverBlueGreenDeployment(deployment.getBlueGreenDeploymentIdentifier(), null, REGION);

        DbInstance production = rdsService.getDbInstance("bg-blue");
        DbInstance retired = rdsService.getDbInstance("bg-blue-old1");
        assertEquals("17.4", production.getEngineVersion());
        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("bg-blue", ACCOUNT, REGION), 5432),
                production.getEndpoint());
        assertEquals(blueAddress, production.getVpcEndpointAddress());
        assertEquals(AwsEndpoints.rdsInstanceHost("bg-blue-old1", ACCOUNT, REGION), retired.getEndpoint().address());
        assertNotEquals(blueAddress, retired.getVpcEndpointAddress());
        assertEquals(Optional.of(blueAddress),
                endpointAddresses.addressOf(AwsEndpoints.rdsInstanceHost("bg-blue", ACCOUNT, REGION)));
        assertEquals(Optional.of(retired.getVpcEndpointAddress()),
                endpointAddresses.addressOf(AwsEndpoints.rdsInstanceHost("bg-blue-old1", ACCOUNT, REGION)));
        assertFalse(endpointAddresses.names(AwsEndpoints.rdsInstanceHost(greenId, ACCOUNT, REGION)));
        verify(proxyManager, atLeastOnce()).startProxy(any(), any(), any(), anyBoolean(),
                eq(production.getProxyPort()), any(), anyInt(), eq(production.getEndpoint().address()), any(), any(),
                any(), any(), any());
    }

    @Test
    void aClustersInstanceServesTheClustersPortAtItsOwnAddressUnderItsName() {
        DbCluster cluster = createCluster("orders", 6000);
        // The API reference: an Aurora instance's port "is managed by the cluster".
        DbInstance writer = createMember("orders-1", "orders", 3306);

        String name = AwsEndpoints.rdsInstanceHost("orders-1", ACCOUNT, REGION);
        assertEquals(new DbEndpoint(name, 6000), writer.getEndpoint());
        assertEquals("10.0.1.20", writer.getVpcEndpointAddress());
        assertEquals(new DbEndpoint(clusterHost("orders", "cluster"), 6000), cluster.getEndpoint());
        assertEquals(new DbEndpoint(clusterHost("orders", "cluster-ro"), 6000), cluster.getReaderEndpoint());
        assertEquals(6000, rdsService.connectablePort(cluster));
        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:6000,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:" + writer.getProxyPort() + ",nodelay"));
        assertNotEquals(cluster.getProxyPort(), writer.getProxyPort());
        assertEquals(Optional.of("10.0.1.20"), endpointAddresses.addressOf(name));
    }

    @Test
    void aClusterCreatedWithoutAPortHasItsInstancesServeTheEnginesDefault() {
        createCluster("orders", null);

        assertEquals(5432, createMember("orders-1", "orders", null).getEndpoint().port());
        assertEquals(5432, rdsService.getDbCluster("orders").getEndpoint().port());
    }

    @Test
    void twoClustersBothServe5432EachInstanceAtItsOwnAddress() {
        createCluster("first", 5432);
        createCluster("second", 5432);

        DbInstance first = createMember("first-1", "first", null);
        DbInstance second = createMember("second-1", "second", null);

        assertEquals(5432, first.getEndpoint().port());
        assertEquals(5432, second.getEndpoint().port());
        assertNotEquals(first.getVpcEndpointAddress(), second.getVpcEndpointAddress());
        assertEquals(Optional.of(first.getVpcEndpointAddress()),
                endpointAddresses.addressOf(clusterHost("first", "cluster")));
        assertEquals(Optional.of(second.getVpcEndpointAddress()),
                endpointAddresses.addressOf(clusterHost("second", "cluster")));
    }

    @Test
    void twoClustersBothServeAPortInTheProxyRange() {
        DbCluster first = createCluster("first", 7050);
        DbCluster second = createCluster("second", 7050);

        assertEquals(7050, first.getEndpoint().port());
        assertEquals(7050, second.getEndpoint().port());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());
    }

    @Test
    void aStoppedClusterKeepsItsNamesAcrossARestartAndLeavesItsVpcWithTheSetting() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        rdsService.stopDbCluster("orders", REGION);

        RdsEndpointAddresses restarted = newEndpointAddresses();
        RdsService service = newService(instances, restarted);
        service.restorePersistedRuntime();

        assertEquals(clusterHost("orders", "cluster"), service.getDbCluster("orders").getEndpoint().address());
        assertTrue(restarted.names(clusterHost("orders", "cluster")));
        assertEquals(Optional.empty(), restarted.addressOf(clusterHost("orders", "cluster")));

        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        RdsEndpointAddresses off = newEndpointAddresses();
        RdsService offService = newService(instances, off);
        offService.restorePersistedRuntime();

        DbCluster cluster = offService.getDbCluster("orders");
        assertEquals("localhost", cluster.getEndpoint().address());
        assertEquals(cluster.getEndpoint(), cluster.getReaderEndpoint());
        assertFalse(RdsEndpointAddresses.inVpc(offService.getDbInstance(writer.getDbInstanceIdentifier())));
    }

    @Test
    void theClusterEndpointResolvesToTheWriterAndFollowsAFailover() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        DbInstance reader = createMember("orders-2", "orders", null);
        String clusterName = clusterHost("orders", "cluster");
        String readerName = clusterHost("orders", "cluster-ro");

        assertEquals(Optional.of(writer.getVpcEndpointAddress()), endpointAddresses.addressOf(clusterName));
        for (int i = 0; i < 20; i++) {
            assertEquals(Optional.of(reader.getVpcEndpointAddress()), endpointAddresses.addressOf(readerName));
        }

        rdsService.failoverDbCluster("orders", "orders-2", REGION);

        assertEquals(Optional.of(reader.getVpcEndpointAddress()), endpointAddresses.addressOf(clusterName));
        assertEquals(Optional.of(writer.getVpcEndpointAddress()), endpointAddresses.addressOf(readerName));
    }

    @Test
    void theReaderEndpointResolvesToTheWriterOfAClusterWithNoOtherInstance() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);

        assertEquals(Optional.of(writer.getVpcEndpointAddress()),
                endpointAddresses.addressOf(clusterHost("orders", "cluster-ro")));
    }

    @Test
    void aClusterWithNoInstanceHasItsNamesKnownWithNoAddress() {
        createCluster("orders", 5432);

        assertTrue(endpointAddresses.names(clusterHost("orders", "cluster")));
        assertTrue(endpointAddresses.names(clusterHost("orders", "cluster-ro")));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(clusterHost("orders", "cluster")));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(clusterHost("orders", "cluster-ro")));
    }

    @Test
    void aCustomEndpointHasItsOwnNameAndResolvesToTheInstancesItHolds() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        DbInstance first = createMember("orders-2", "orders", null);
        DbInstance second = createMember("orders-3", "orders", null);

        DbClusterEndpoint reports = rdsService.createDbClusterEndpoint(REGION, "orders", "reports", "ANY",
                List.of("orders-2"), null, Map.of());
        rdsService.createDbClusterEndpoint(REGION, "orders", "readers", "READER", null, List.of("orders-2"), Map.of());
        rdsService.createDbClusterEndpoint(REGION, "orders", "all", "ANY", null, List.of("orders-3"), Map.of());

        String reportsName = AwsEndpoints.rdsClusterEndpointHost("reports", "cluster-custom", ACCOUNT, REGION);
        assertEquals(reportsName, reports.getEndpoint());
        assertEquals(reportsName, rdsService.describeDbClusterEndpoints(REGION, "orders", "reports", null, null, null)
                .endpoints().get(0).getEndpoint());
        assertEquals(Optional.of(first.getVpcEndpointAddress()), endpointAddresses.addressOf(reportsName));
        // A READER endpoint leaves out the writer; an exclusion list holds every other instance.
        assertEquals(Optional.of(second.getVpcEndpointAddress()), endpointAddresses.addressOf(
                AwsEndpoints.rdsClusterEndpointHost("readers", "cluster-custom", ACCOUNT, REGION)));
        String allName = AwsEndpoints.rdsClusterEndpointHost("all", "cluster-custom", ACCOUNT, REGION);
        for (int i = 0; i < 20; i++) {
            assertTrue(List.of(writer.getVpcEndpointAddress(), first.getVpcEndpointAddress())
                    .contains(endpointAddresses.addressOf(allName).orElseThrow()));
        }

        rdsService.modifyDbClusterEndpoint(REGION, "reports", null, List.of("orders-3"), null);
        assertEquals(Optional.of(second.getVpcEndpointAddress()), endpointAddresses.addressOf(reportsName));

        rdsService.deleteDbClusterEndpoint(REGION, "reports");
        assertFalse(endpointAddresses.names(reportsName));
    }

    @Test
    void aStaticCustomEndpointWhoseMembersAreAllDeletedResolvesToNoAddress() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        createMember("orders-2", "orders", null);
        rdsService.createDbClusterEndpoint(REGION, "orders", "reports", "ANY", List.of("orders-2"), null, Map.of());
        rdsService.createDbClusterEndpoint(REGION, "orders", "everyone", "ANY", null, null, Map.of());
        String reportsName = AwsEndpoints.rdsClusterEndpointHost("reports", "cluster-custom", ACCOUNT, REGION);

        rdsService.deleteDbInstance("orders-2", REGION);

        assertTrue(endpointAddresses.names(reportsName));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(reportsName));
        // One created with neither list holds every instance.
        assertEquals(Optional.of(writer.getVpcEndpointAddress()), endpointAddresses.addressOf(
                AwsEndpoints.rdsClusterEndpointHost("everyone", "cluster-custom", ACCOUNT, REGION)));

        rdsService.modifyDbClusterEndpoint(REGION, "reports", null, null, List.of());
        assertEquals(Optional.of(writer.getVpcEndpointAddress()), endpointAddresses.addressOf(reportsName));
    }

    @Test
    void aCustomEndpointStoredBeforeItsKindWasRecordedHoldsOnlyWhatItsListsSay() {
        List<String> members = List.of("writer", "first", "second");
        DbClusterEndpoint emptied = new DbClusterEndpoint();
        emptied.setCustomEndpointType("ANY");
        DbClusterEndpoint excluding = new DbClusterEndpoint();
        excluding.setCustomEndpointType("ANY");
        excluding.setExcludedMembers(new ArrayList<>(List.of("first")));
        DbClusterEndpoint listing = new DbClusterEndpoint();
        listing.setCustomEndpointType("READER");
        listing.setStaticMembers(new ArrayList<>(List.of("writer", "second")));

        assertEquals(List.of(), RdsService.customEndpointMembers(emptied, members, "writer"));
        assertEquals(List.of("writer", "second"), RdsService.customEndpointMembers(excluding, members, "writer"));
        assertEquals(List.of("second"), RdsService.customEndpointMembers(listing, members, "writer"));
    }

    @Test
    void aMultiAzOrServerlessV1ClusterKeepsTheProxyEndpoint() {
        DbCluster multiAz = rdsService.createDbCluster("multi-az", "postgres", "16.3", "admin", "password", "app",
                false, null, null, null, false, REGION, null, null, null, false, null, null, false, 7050);
        DbCluster serverless = rdsService.createDbCluster("serverless", "aurora-mysql", "8.0", "admin", "password",
                "app", false, null, null, null, false, REGION, null, null, null, false, null, "serverless", false,
                null);

        for (DbCluster cluster : List.of(multiAz, serverless)) {
            assertEquals("localhost", cluster.getEndpoint().address());
            assertFalse(endpointAddresses.names(clusterHost(cluster.getDbClusterIdentifier(), "cluster")));
        }
        // A Port in the proxy range is the proxy port, as it is with the setting off.
        assertEquals(7050, multiAz.getProxyPort());
        DbInstance member = createMember("serverless-1", "serverless", 7060);
        assertFalse(RdsEndpointAddresses.inVpc(member));
        assertEquals(7060, member.getProxyPort());
    }

    @Test
    void aTokenGeneratedForAnyOfAClustersNamesIsGoodAtItsInstances() {
        createCluster("orders", 5432);
        createCluster("other", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        rdsService.createDbClusterEndpoint(REGION, "orders", "reports", "ANY", null, null, Map.of());

        ArgumentCaptor<RdsProxyBinding> binding = ArgumentCaptor.forClass(RdsProxyBinding.class);
        verify(proxyManager).startProxy(any(), any(), any(), anyBoolean(), eq(writer.getProxyPort()), any(),
                anyInt(), eq(writer.getEndpoint().address()), any(), any(), any(), any(), binding.capture());
        assertEquals(5432, binding.getValue().publishedPort());
        assertTrue(binding.getValue().acceptsHost(writer.getEndpoint().address()));
        assertTrue(binding.getValue().acceptsHost(clusterHost("orders", "cluster").toUpperCase()));
        assertTrue(binding.getValue().acceptsHost(clusterHost("orders", "cluster-ro")));
        assertTrue(binding.getValue().acceptsHost(
                AwsEndpoints.rdsClusterEndpointHost("reports", "cluster-custom", ACCOUNT, REGION)));
        assertFalse(binding.getValue().acceptsHost(clusterHost("other", "cluster")));
        verify(proxyManager, atLeastOnce()).coverTlsHost(clusterHost("orders", "cluster-ro"));
    }

    @Test
    void stoppingAClusterRemovesItsInstancesRelaysAndStartingServesThemAgain() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        String address = writer.getVpcEndpointAddress();

        rdsService.stopDbCluster("orders", REGION);

        assertTrue(fixture.present.isEmpty());
        assertEquals(Optional.empty(), endpointAddresses.addressOf(clusterHost("orders", "cluster")));
        assertTrue(endpointAddresses.names(clusterHost("orders", "cluster")));

        rdsService.startDbCluster("orders", REGION);

        assertEquals(1, fixture.present.size());
        assertEquals(Optional.of(address), endpointAddresses.addressOf(clusterHost("orders", "cluster")));
    }

    @Test
    void deletingAnInstanceDropsItFromTheNamesAndDeletingTheClusterForgetsThem() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        DbInstance reader = createMember("orders-2", "orders", null);

        rdsService.deleteDbInstance("orders-1", REGION);

        assertFalse(fixture.present.contains("relay-1"));
        assertFalse(endpointAddresses.names(writer.getEndpoint().address()));
        // The remaining instance is the writer now.
        assertEquals(Optional.of(reader.getVpcEndpointAddress()),
                endpointAddresses.addressOf(clusterHost("orders", "cluster")));

        rdsService.deleteDbInstance("orders-2", REGION);
        rdsService.deleteDbCluster("orders", REGION);

        assertTrue(fixture.present.isEmpty());
        assertFalse(endpointAddresses.names(clusterHost("orders", "cluster")));
        assertFalse(endpointAddresses.names(clusterHost("orders", "cluster-ro")));
    }

    @Test
    void anInstanceWhoseCreateFailsLeavesNoNameBehind() {
        createCluster("orders", 5432);
        ((FailingStore) instances).failNextAvailableWrite = true;

        assertThrows(RuntimeException.class, () -> createMember("orders-1", "orders", null));

        assertFalse(endpointAddresses.names(AwsEndpoints.rdsInstanceHost("orders-1", ACCOUNT, REGION)));
        assertTrue(fixture.present.isEmpty());
        assertTrue(rdsService.getDbCluster("orders").getDbClusterMembers().isEmpty());
        assertEquals(Optional.empty(), endpointAddresses.addressOf(clusterHost("orders", "cluster")));
    }

    @Test
    void aClusterInItsVpcIsConnectableOnThePortItReportsWhateverAdvertiseRequestedPortSays() {
        when(config.services().rds().advertiseRequestedPort()).thenReturn(true);

        DbCluster cluster = createCluster("orders", 6000);

        assertEquals(6000, rdsService.connectablePort(cluster));
        assertEquals(clusterHost("orders", "cluster"), cluster.getEndpoint().address());
    }

    @Test
    void anInstanceOfAClusterWithNoDatabaseYetHasItsNameKnownWithNoAddress() {
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        createCluster("orders", 5432);

        DbInstance writer = createMember("orders-1", "orders", null);

        assertTrue(endpointAddresses.names(writer.getEndpoint().address()));
        assertEquals(Optional.empty(), endpointAddresses.addressOf(writer.getEndpoint().address()));
        assertTrue(fixture.present.isEmpty());
    }

    @Test
    void anInstanceWhoseFailedCreateLeavesItsRelayIsNoLongerBehindTheClustersNames() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        ((FailingStore) instances).failNextAvailableWrite = true;
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-2");

        assertThrows(RuntimeException.class, () -> createMember("orders-2", "orders", null));

        assertTrue(fixture.present.contains("relay-2"));
        for (int i = 0; i < 20; i++) {
            assertEquals(Optional.of(writer.getVpcEndpointAddress()),
                    endpointAddresses.addressOf(clusterHost("orders", "cluster-ro")));
        }
    }

    @Test
    void aRestartServesAClustersInstancesAndNamesAgain() {
        createCluster("orders", 5432);
        DbInstance writer = createMember("orders-1", "orders", null);
        String address = writer.getVpcEndpointAddress();
        endpointAddresses.stopManagedContainers();

        RdsEndpointAddresses restarted = newEndpointAddresses();
        newService(instances, restarted).restorePersistedRuntime();

        assertEquals(Optional.of(address), restarted.addressOf(clusterHost("orders", "cluster")));
        assertEquals(Optional.of(address), restarted.addressOf(clusterHost("orders", "cluster-ro")));
        assertEquals(Optional.of(address), restarted.addressOf(writer.getEndpoint().address()));
    }

    @Test
    void aRestartWithTheSettingOffTakesAClusterOutOfItsVpc() {
        createCluster("orders", 5432);
        createMember("orders-1", "orders", null);
        endpointAddresses.stopManagedContainers();
        when(config.services().rds().endpointsInVpc()).thenReturn(false);

        RdsEndpointAddresses restarted = newEndpointAddresses();
        RdsService service = newService(instances, restarted);
        service.restorePersistedRuntime();

        DbCluster cluster = service.getDbCluster("orders");
        DbInstance member = service.getDbInstance("orders-1");
        assertEquals("localhost", cluster.getEndpoint().address());
        assertEquals(cluster.getEndpoint(), cluster.getReaderEndpoint());
        assertEquals(cluster.getProxyPort(), cluster.getEndpoint().port());
        assertFalse(RdsEndpointAddresses.inVpc(member));
        assertEquals("localhost", member.getEndpoint().address());
    }

    @Test
    void aRestartWithTheSettingOnPutsAClusterInItsVpcWithItsInstancesOnItsPort() {
        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        createCluster("orders", 6000);
        createMember("orders-1", "orders", 3306);
        createCluster("archive", 5432);
        rdsService.stopDbCluster("archive", REGION);
        when(config.services().rds().endpointsInVpc()).thenReturn(true);

        RdsEndpointAddresses restarted = newEndpointAddresses();
        RdsService service = newService(instances, restarted);
        service.restorePersistedRuntime();

        DbInstance member = service.getDbInstance("orders-1");
        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("orders-1", ACCOUNT, REGION), 6000),
                member.getEndpoint());
        assertEquals(Optional.of(member.getVpcEndpointAddress()),
                restarted.addressOf(clusterHost("orders", "cluster")));
        assertEquals(new DbEndpoint(clusterHost("orders", "cluster"), 6000),
                service.getDbCluster("orders").getEndpoint());
        assertEquals(new DbEndpoint(clusterHost("archive", "cluster-ro"), 5432),
                service.getDbCluster("archive").getReaderEndpoint());
        assertTrue(restarted.names(clusterHost("archive", "cluster")));
    }

    @Test
    void aMultiAzClusterRestoredFromASnapshotTakesAFreeProxyPortWhileItsOriginalHoldsItsOwn() {
        DbCluster original = rdsService.createDbCluster("multi-az", "postgres", "16.3", "admin", "password", "app",
                false, null, null, null, false, REGION, null, null, null, false, null, null, false, 7050);
        when(containerManager.createPostgresSnapshot(any(), any())).thenReturn("DUMP");
        rdsService.createDbClusterSnapshot("multi-az-snap", "multi-az", Map.of(), REGION);

        DbCluster restored = rdsService.restoreDbClusterFromSnapshot("multi-az-copy", "multi-az-snap", "postgres",
                null, null, null, null, null, null, null, null, false, Map.of(), REGION);

        assertEquals(7050, original.getProxyPort());
        assertNotEquals(7050, restored.getProxyPort());
        assertEquals("localhost", restored.getEndpoint().address());
    }

    @Test
    void aClusterRestoredFromASnapshotServesItsOriginalsPort() {
        createCluster("orders", 6000);
        when(containerManager.createPostgresSnapshot(any(), any())).thenReturn("DUMP");
        rdsService.createDbClusterSnapshot("orders-snap", "orders", Map.of(), REGION);

        DbCluster restored = rdsService.restoreDbClusterFromSnapshot("orders-copy", "orders-snap",
                "aurora-postgresql", null, null, null, null, null, null, null, null, false, Map.of(), REGION);

        assertEquals(new DbEndpoint(clusterHost("orders-copy", "cluster"), 6000), restored.getEndpoint());
    }

    private DbCluster createCluster(String id, Integer port) {
        return rdsService.createDbCluster(id, "aurora-postgresql", "16.3", "admin", "password", "app", false, null,
                null, null, false, REGION, null, null, null, false, null, null, false, port);
    }

    private DbInstance createMember(String id, String clusterId, Integer port) {
        return rdsService.createDbInstance(id, "aurora-postgresql", "16.3", null, null, "app", "db.r6g.large", 20,
                false, null, null, clusterId, null, false, false, null, Map.of(), List.of(), null, null, true,
                DbInstanceSettings.defaults(), null, port);
    }

    private static String clusterHost(String clusterId, String type) {
        return AwsEndpoints.rdsClusterEndpointHost(clusterId, type, ACCOUNT, REGION);
    }

    private DbInstance create(String id, String engine, Integer port) {
        return rdsService.createDbInstance(id, engine, "mysql".equals(engine) ? "8.0.36" : "16.3", "admin", "password", "app", "db.t3.micro", 20, false,
                null, null, null, null, false, false, null, Map.of(), List.of(), null, null, true,
                DbInstanceSettings.defaults(), null, port);
    }

    private RdsService newService(StorageBackend<String, DbInstance> store) {
        return newService(store, endpointAddresses);
    }

    private RdsService newService(StorageBackend<String, DbInstance> store, RdsEndpointAddresses addresses) {
        return new RdsService(containerManager, proxyManager, ec2Service, new RegionResolver(REGION, ACCOUNT),
                config, store, clusters, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, null, null, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, null, null, addresses);
    }

    /** What a Floci process starts with: no relay served and no name known. */
    private RdsEndpointAddresses newEndpointAddresses() {
        return new RdsEndpointAddresses(config, new RegionResolver(REGION, ACCOUNT), hostResolver,
                fixture.relays(RdsEndpointAddresses.SERVICE, null));
    }

    private RdsService newServiceView() {
        return newService(instances);
    }

    /** A store whose next write of an available instance fails, as a full disk would make it. */
    private static final class FailingStore extends InMemoryStorage<String, DbInstance> {
        boolean failNextAvailableWrite;

        @Override
        public synchronized void put(String key, DbInstance value) {
            if (failNextAvailableWrite && value.getStatus() == DbInstanceStatus.AVAILABLE) {
                failNextAvailableWrite = false;
                throw new IllegalStateException("store refused the write");
            }
            super.put(key, value);
        }
    }

    private static Subnet subnet(String id, String availabilityZone) {
        return subnet(id, availabilityZone, "vpc-default");
    }

    private static Subnet subnet(String id, String availabilityZone, String vpcId) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(id);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setCidrBlock("10.0.1.0/24");
        return subnet;
    }
}
