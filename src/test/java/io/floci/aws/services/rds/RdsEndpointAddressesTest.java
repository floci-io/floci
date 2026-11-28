package io.floci.aws.services.rds;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.VpcRelayFixture;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbEndpoint;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.DbInstanceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Where an instance's endpoint in its VPC is served, what it reports, and what a cluster's names resolve to. */
class RdsEndpointAddressesTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";

    private EmulatorConfig config;
    private VpcRelayFixture fixture;
    private RdsEndpointAddresses addresses;
    private DockerHostResolver hostResolver;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().rds().endpointsInVpc()).thenReturn(true);
        when(config.services().rds().mock()).thenReturn(false);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        Ec2Service ec2 = mock(Ec2Service.class);
        for (String subnetId : List.of("subnet-a", "subnet-b")) {
            Subnet subnet = new Subnet();
            subnet.setSubnetId(subnetId);
            subnet.setVpcId("vpc-1");
            when(ec2.describeSubnets(REGION, List.of(subnetId), Map.of())).thenReturn(List.of(subnet));
        }
        fixture = new VpcRelayFixture(ec2);
        hostResolver = mock(DockerHostResolver.class);
        when(hostResolver.resolve()).thenReturn("host.docker.internal");
        when(hostResolver.resolveOn(any())).thenReturn("host.docker.internal");
        addresses = new RdsEndpointAddresses(config, new RegionResolver(REGION, ACCOUNT), hostResolver,
                fixture.relays(RdsEndpointAddresses.SERVICE, null));
    }

    @Test
    void anInstanceIsServedInTheSubnetOfItsAvailabilityZone() {
        DbInstance instance = instance("db1", "us-east-1b", null);

        assertTrue(addresses.assign(instance));

        assertEquals("subnet-b", instance.getVpcEndpointSubnetId());
        assertTrue(RdsEndpointAddresses.inVpc(instance));
        assertNull(instance.getVpcEndpointAddress());
    }

    @Test
    void anInstanceWithNoSubnetInItsZoneIsServedInTheFirstSubnetOfItsGroup() {
        DbInstance instance = instance("db1", "us-east-1c", null);

        assertTrue(addresses.assign(instance));

        assertEquals("subnet-a", instance.getVpcEndpointSubnetId());
    }

    @Test
    void endpointsStayOnFloci() {
        DbInstance off = instance("off", "us-east-1a", null);
        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        assertFalse(addresses.assign(off));

        when(config.services().rds().endpointsInVpc()).thenReturn(true);
        when(config.services().rds().mock()).thenReturn(true);
        assertFalse(addresses.assign(off));

        when(config.services().rds().mock()).thenReturn(false);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(false);
        assertFalse(addresses.assign(off));

        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(true);
        assertFalse(addresses.assign(off));

        when(config.services().ec2().mock()).thenReturn(false);
        DbInstance noSubnet = instance("nosubnet", "us-east-1a", null);
        noSubnet.setSubnetAvailabilityZones(new LinkedHashMap<>());
        assertFalse(addresses.assign(noSubnet));

        assertFalse(RdsEndpointAddresses.inVpc(off));
        assertFalse(RdsEndpointAddresses.inVpc(noSubnet));
    }

    @Test
    void theEndpointIsTheInstanceNameRdsGivesItOnThePortItServes() {
        DbInstance postgres = instance("App-DB", "us-east-1a", null);
        DbInstance mysql = instance("other", "us-east-1a", null);
        mysql.setEngine(DatabaseEngine.MYSQL);
        DbInstance requested = instance("custom", "us-east-1a", 6543);

        assertEquals(new DbEndpoint(AwsEndpoints.rdsInstanceHost("app-db", ACCOUNT, REGION), 5432),
                RdsEndpointAddresses.endpoint(postgres, ACCOUNT, REGION));
        assertEquals(3306, RdsEndpointAddresses.endpoint(mysql, ACCOUNT, REGION).port());
        assertEquals(6543, RdsEndpointAddresses.endpoint(requested, ACCOUNT, REGION).port());
    }

    @Test
    void servingRelaysThePortTheInstanceServesToItsProxyAndRecordsTheAddress() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);

        addresses.serve(instance, ACCOUNT, REGION, 7004);

        assertEquals("10.0.1.20", instance.getVpcEndpointAddress());
        assertTrue(addresses.serving(instance));
        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:7004,nodelay"));
        verify(fixture.ec2).attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-a", "relay-1", "10.0.1.20");
    }

    @Test
    void aRelayReachesTheProxyAtFlocisAddressOnTheNetworkItJoins() {
        when(fixture.containerBuilder.resolveDockerNetwork(Optional.empty())).thenReturn(Optional.of("rds-net"));
        when(hostResolver.resolveOn("rds-net")).thenReturn("172.30.0.5");
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);

        addresses.serve(instance, ACCOUNT, REGION, 7004);

        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:172.30.0.5:7004,nodelay"));
    }

    @Test
    void twoInstancesServeTheSamePortEachAtItsOwnAddress() {
        DbInstance first = instance("first", "us-east-1a", 5432);
        DbInstance second = instance("second", "us-east-1a", 5432);
        addresses.assign(first);
        addresses.assign(second);

        addresses.serve(first, ACCOUNT, REGION, 7001);
        addresses.serve(second, ACCOUNT, REGION, 7002);

        assertEquals("10.0.1.20", first.getVpcEndpointAddress());
        assertEquals("10.0.1.21", second.getVpcEndpointAddress());
        assertEquals(5432, RdsEndpointAddresses.endpoint(first, ACCOUNT, REGION).port());
        assertEquals(5432, RdsEndpointAddresses.endpoint(second, ACCOUNT, REGION).port());
    }

    @Test
    void aStoppedEndpointIsServedAgainAtTheAddressItHeld() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, 7001);

        addresses.stop(instance);
        assertFalse(addresses.serving(instance));
        assertEquals("10.0.1.20", instance.getVpcEndpointAddress());
        addresses.serve(instance, ACCOUNT, REGION, 7001);

        assertEquals("10.0.1.20", instance.getVpcEndpointAddress());
        verify(fixture.ec2).attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-a", "relay-2", "10.0.1.20");
    }

    @Test
    void anEndpointTakenOutOfItsVpcLosesItsRelayAndAddress() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, 7001);

        addresses.unassign(instance);

        assertFalse(RdsEndpointAddresses.inVpc(instance));
        assertNull(instance.getVpcEndpointSubnetId());
        assertNull(instance.getVpcEndpointAddress());
        assertFalse(fixture.leased.contains("10.0.1.20"));
    }

    @Test
    void aServedEndpointIsKnownByItsNameWithItsAddressAndAStoppedOneWithNone() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);
        String host = AwsEndpoints.rdsInstanceHost("db1", ACCOUNT, REGION);
        assertFalse(addresses.names(host));

        addresses.serve(instance, ACCOUNT, REGION, 7001);
        assertTrue(addresses.names(host.toUpperCase()));
        assertEquals(Optional.of("10.0.1.20"), addresses.addressOf(host));

        addresses.stop(instance);
        assertTrue(addresses.names(host));
        assertEquals(Optional.empty(), addresses.addressOf(host));

        addresses.forget(instance);
        assertFalse(addresses.names(host));
    }

    @Test
    void anEndpointRememberedUnderANewIdentifierLosesItsOldName() {
        DbInstance instance = instance("old-name", "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, 7001);

        instance.setDbInstanceIdentifier("new-name");
        addresses.remember(instance, ACCOUNT, REGION);

        assertFalse(addresses.names(AwsEndpoints.rdsInstanceHost("old-name", ACCOUNT, REGION)));
        assertEquals(Optional.of("10.0.1.20"),
                addresses.addressOf(AwsEndpoints.rdsInstanceHost("new-name", ACCOUNT, REGION)));
    }

    @Test
    void anEndpointWhoseRelayDockerDidNotRemoveKeepsItsName() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, 7001);
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");

        assertThrows(IllegalStateException.class, () -> addresses.unassign(instance));

        assertTrue(RdsEndpointAddresses.inVpc(instance));
        assertTrue(addresses.names(AwsEndpoints.rdsInstanceHost("db1", ACCOUNT, REGION)));
        assertTrue(addresses.serving(instance));
    }

    @Test
    void aServedEndpointKeepsItsSubnetAndVpcInUseForTheAccountThatOwnsIt() {
        DbInstance instance = instance("db1", "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, 7001);

        assertTrue(addresses.hasSubnetDependency(REGION, "subnet-a"));
        assertTrue(addresses.hasVpcDependency(REGION, "vpc-1"));
        assertFalse(addresses.hasSubnetDependency(REGION, "subnet-b"));

        addresses.stopManagedContainers();

        assertFalse(addresses.hasSubnetDependency(REGION, "subnet-a"));
        assertFalse(addresses.serving(instance));
    }

    @Test
    void aClusterNameResolvesToOneOfItsServedInstancesAtRandom() {
        DbInstance first = served("db1", 7001);
        DbInstance second = served("db2", 7002);
        addresses.stop(served("db3", 7003));
        String name = AwsEndpoints.rdsClusterEndpointHost("reports", "cluster-custom", ACCOUNT, REGION);
        addresses.route("cluster-1", Map.of(name, clusterName("cluster-1", List.of("db1", "db2", "db3"), List.of())));

        Set<String> answers = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            answers.add(addresses.addressOf(name.toUpperCase()).orElseThrow());
        }

        assertEquals(Set.of(first.getVpcEndpointAddress(), second.getVpcEndpointAddress()), answers);
        assertTrue(addresses.names(name));
    }

    @Test
    void aClusterNameWithNoInstanceServedResolvesToTheOtherwiseInstances() {
        DbInstance writer = served("writer", 7001);
        String reader = AwsEndpoints.rdsClusterEndpointHost("orders", "cluster-ro", ACCOUNT, REGION);
        addresses.route("cluster-1", Map.of(reader, clusterName("cluster-1", List.of("gone"), List.of("writer"))));

        assertEquals(Optional.of(writer.getVpcEndpointAddress()), addresses.addressOf(reader));

        addresses.route("cluster-1", Map.of(reader, clusterName("cluster-1", List.of(), List.of())));
        assertTrue(addresses.names(reader));
        assertEquals(Optional.empty(), addresses.addressOf(reader));
    }

    @Test
    void routingAClusterAgainReplacesItsNamesAndLeavesOtherClustersAlone() {
        String reports = AwsEndpoints.rdsClusterEndpointHost("reports", "cluster-custom", ACCOUNT, REGION);
        String writer = AwsEndpoints.rdsClusterEndpointHost("orders", "cluster", ACCOUNT, REGION);
        String other = AwsEndpoints.rdsClusterEndpointHost("other", "cluster", ACCOUNT, REGION);
        addresses.route("cluster-1", Map.of(reports, clusterName("cluster-1", List.of(), List.of()),
                writer, clusterName("cluster-1", List.of(), List.of())));
        addresses.route("cluster-2", Map.of(other, clusterName("cluster-2", List.of(), List.of())));

        addresses.route("cluster-1", Map.of(writer, clusterName("cluster-1", List.of(), List.of())));

        assertFalse(addresses.names(reports));
        assertTrue(addresses.reaches("cluster-1", writer.toUpperCase()));
        assertFalse(addresses.reaches("cluster-2", writer));
        assertTrue(addresses.reaches("cluster-2", other));

        addresses.forgetCluster("cluster-1");

        assertFalse(addresses.names(writer));
        assertTrue(addresses.names(other));
        addresses.clear();
        assertFalse(addresses.names(other));
    }

    @Test
    void aClusterIsInItsVpcWithASubnetAndEndpointsInTheirVpc() {
        DbCluster cluster = new DbCluster();
        cluster.setDbClusterIdentifier("Orders");
        cluster.setEngine(DatabaseEngine.MYSQL);
        cluster.setSubnetAvailabilityZones(new LinkedHashMap<>(Map.of("subnet-a", "us-east-1a")));
        assertTrue(addresses.inVpc(cluster));
        assertEquals(new DbEndpoint(AwsEndpoints.rdsClusterEndpointHost("orders", "cluster", ACCOUNT, REGION), 3306),
                RdsEndpointAddresses.clusterEndpoint(cluster, ACCOUNT, REGION));
        cluster.setRequestedPort(6000);
        assertEquals(new DbEndpoint(AwsEndpoints.rdsClusterEndpointHost("orders", "cluster-ro", ACCOUNT, REGION), 6000),
                RdsEndpointAddresses.readerEndpoint(cluster, ACCOUNT, REGION));

        when(config.services().rds().endpointsInVpc()).thenReturn(false);
        assertFalse(addresses.inVpc(cluster));
        when(config.services().rds().endpointsInVpc()).thenReturn(true);
        cluster.setSubnetAvailabilityZones(new LinkedHashMap<>());
        assertFalse(addresses.inVpc(cluster));
    }

    @Test
    void aProxysEndpointIsServedUnderItsNameAndReachesItsProxyAlone() {
        String relayId = RdsEndpointAddresses.newRelayId();
        String name = AwsEndpoints.rdsProxyEndpointHost("reader", ACCOUNT, REGION);

        String address = addresses.serve(relayId, ACCOUNT, REGION, "vpc-1", "subnet-a", 5432, 7005, "proxy endpoint",
                null);
        addresses.remember(name, relayId, "prx-1");

        assertEquals("10.0.1.20", address);
        assertEquals(Optional.of(address), addresses.addressOf(name.toUpperCase()));
        assertTrue(addresses.reaches("prx-1", name.toUpperCase()));
        assertFalse(addresses.reaches("prx-2", name));
        assertTrue(addresses.targetsPort(7005));
        verify(fixture.builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:7005,nodelay"));

        String renamed = AwsEndpoints.rdsProxyEndpointHost("reports", ACCOUNT, REGION);
        addresses.remember(renamed, relayId, "prx-1");
        assertFalse(addresses.names(name));
        assertTrue(addresses.reaches("prx-1", renamed));

        addresses.discard(relayId);

        assertFalse(addresses.names(renamed));
        assertFalse(addresses.reaches("prx-1", renamed));
        assertFalse(addresses.targetsPort(7005));
    }

    @Test
    void aDiscardedEndpointWhoseRelayDockerKeepsLosesItsNameAndKeepsItsPort() {
        String relayId = RdsEndpointAddresses.newRelayId();
        String name = AwsEndpoints.rdsProxyHost("app-proxy", ACCOUNT, REGION);
        addresses.serve(relayId, ACCOUNT, REGION, "vpc-1", "subnet-a", 5432, 7005, "proxy", null);
        addresses.remember(name, relayId, "prx-1");
        doThrow(new IllegalStateException("daemon down")).when(fixture.lifecycleManager)
                .removeIfExistsStrict("relay-1");

        addresses.discard(relayId);

        assertFalse(addresses.names(name));
        assertFalse(addresses.reaches("prx-1", name));
        assertTrue(addresses.targetsPort(7005));
        assertTrue(addresses.hasSubnetDependency(REGION, "subnet-a"));
    }

    @Test
    void aResetForgetsWhichProxyAnEndpointReaches() {
        String relayId = RdsEndpointAddresses.newRelayId();
        String name = AwsEndpoints.rdsProxyEndpointHost("reader", ACCOUNT, REGION);
        addresses.remember(name, relayId, "prx-1");

        addresses.clear();

        assertFalse(addresses.reaches("prx-1", name));
    }

    /** An instance served at its own address, with its name known. */
    private DbInstance served(String id, int proxyPort) {
        DbInstance instance = instance(id, "us-east-1a", null);
        addresses.assign(instance);
        addresses.serve(instance, ACCOUNT, REGION, proxyPort);
        return instance;
    }

    private static RdsEndpointAddresses.ClusterName clusterName(String clusterResourceId, List<String> instances,
                                                                List<String> otherwise) {
        return new RdsEndpointAddresses.ClusterName(clusterResourceId,
                instances.stream().map(id -> AwsEndpoints.rdsInstanceHost(id, ACCOUNT, REGION)).toList(),
                otherwise.stream().map(id -> AwsEndpoints.rdsInstanceHost(id, ACCOUNT, REGION)).toList());
    }

    private static DbInstance instance(String id, String availabilityZone, Integer requestedPort) {
        DbInstance instance = new DbInstance(id, DatabaseEngine.POSTGRES, "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, DbInstanceStatus.AVAILABLE, null, false, null, null, Instant.now(), 7001);
        instance.setDbInstanceArn("arn:aws:rds:" + REGION + ":" + ACCOUNT + ":db:" + id);
        instance.setVpcId("vpc-1");
        instance.setAvailabilityZone(availabilityZone);
        Map<String, String> subnets = new LinkedHashMap<>();
        subnets.put("subnet-a", "us-east-1a");
        subnets.put("subnet-b", "us-east-1b");
        instance.setSubnetAvailabilityZones(subnets);
        instance.setRequestedPort(requestedPort);
        return instance;
    }
}
