package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceAttachment;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksVpcRouteProgrammingTest {

    @Test
    void buildRoutingCommandReturnsEmptyWhenNoRoutes() {
        Optional<String[]> cmdNull = EksVpcRouteProgramming.buildRoutingCommand(null);
        assertTrue(cmdNull.isEmpty());

        Optional<String[]> cmdEmpty = EksVpcRouteProgramming.buildRoutingCommand(List.of());
        assertTrue(cmdEmpty.isEmpty());
    }

    @Test
    void buildRoutingCommandGeneratesIdempotentScript() {
        List<EksVpcRouteProgramming.VpcRouteEntry> entries = List.of(
                new EksVpcRouteProgramming.VpcRouteEntry("10.20.0.0/16", "172.18.0.5"),
                new EksVpcRouteProgramming.VpcRouteEntry("10.30.0.0/16", "172.18.0.6")
        );

        Optional<String[]> cmdOpt = EksVpcRouteProgramming.buildRoutingCommand(entries);
        assertTrue(cmdOpt.isPresent());
        String[] cmd = cmdOpt.get();
        assertEquals(3, cmd.length);
        assertEquals("sh", cmd[0]);
        assertEquals("-c", cmd[1]);

        String script = cmd[2];
        assertTrue(script.contains("command -v ip"));
        assertTrue(script.contains("$IP route replace \"10.20.0.0/16\" via \"172.18.0.5\""));
        assertTrue(script.contains("$IP route replace \"10.30.0.0/16\" via \"172.18.0.6\""));
        assertTrue(script.contains("/run/floci-vpc-routes.txt"));
    }

    @Test
    void buildRoutingCommandGeneratesDeleteForStaleRoutes() {
        List<EksVpcRouteProgramming.VpcRouteEntry> entries = List.of(
                new EksVpcRouteProgramming.VpcRouteEntry("10.20.0.0/16", "172.18.0.5")
        );
        Set<String> staleRoutes = Set.of("10.99.0.0/16", "10.88.0.0/24");

        Optional<String[]> cmdOpt = EksVpcRouteProgramming.buildRoutingCommand(entries, staleRoutes);
        assertTrue(cmdOpt.isPresent());
        String script = cmdOpt.get()[2];

        assertTrue(script.contains("$IP route del \"10.99.0.0/16\""));
        assertTrue(script.contains("$IP route del \"10.88.0.0/24\""));
        assertTrue(script.contains("$IP route replace \"10.20.0.0/16\" via \"172.18.0.5\""));
        assertTrue(script.contains("/run/floci-vpc-routes.txt"));
    }

    @Test
    void rejectsMalformedDestinationAndGatewayToPreventCommandInjection() {
        assertFalse(EksVpcRouteProgramming.isValidIpv4Cidr("10.123.0.0/16; id >/tmp/proof; #"));
        assertFalse(EksVpcRouteProgramming.isValidIpv4Cidr("10.0.0.0/33"));
        assertFalse(EksVpcRouteProgramming.isValidIpv4Cidr("10.0.0.256/16"));
        assertTrue(EksVpcRouteProgramming.isValidIpv4Cidr("10.0.0.0/16"));

        assertFalse(EksVpcRouteProgramming.isValidIpv4Address("172.18.0.5; rm -rf /"));
        assertFalse(EksVpcRouteProgramming.isValidIpv4Address("172.18.0.256"));
        assertTrue(EksVpcRouteProgramming.isValidIpv4Address("172.18.0.5"));

        // When passed into buildRoutingCommand, invalid entries are skipped
        List<EksVpcRouteProgramming.VpcRouteEntry> maliciousEntries = List.of(
                new EksVpcRouteProgramming.VpcRouteEntry("10.123.0.0/16; id >/tmp/proof; #", "172.18.0.5"),
                new EksVpcRouteProgramming.VpcRouteEntry("10.20.0.0/16", "172.18.0.5; cat /etc/passwd")
        );
        Optional<String[]> cmdOpt = EksVpcRouteProgramming.buildRoutingCommand(maliciousEntries);
        assertTrue(cmdOpt.isEmpty());
    }

    @Test
    void findApplicableRouteTablesMatchesExplicitSubnetAssociations() {
        String vpcId = "vpc-test";
        String subnetA = "subnet-a";
        String subnetB = "subnet-b";
        String subnetC = "subnet-c";

        RouteTable mainTable = new RouteTable();
        mainTable.setRouteTableId("rtb-main");
        mainTable.setVpcId(vpcId);
        RouteTableAssociation mainAssoc = new RouteTableAssociation();
        mainAssoc.setMain(true);
        mainTable.setAssociations(List.of(mainAssoc));

        RouteTable customTableA = new RouteTable();
        customTableA.setRouteTableId("rtb-sub-a");
        customTableA.setVpcId(vpcId);
        RouteTableAssociation assocA = new RouteTableAssociation();
        assocA.setSubnetId(subnetA);
        customTableA.setAssociations(List.of(assocA));

        RouteTable customTableC = new RouteTable();
        customTableC.setRouteTableId("rtb-sub-c");
        customTableC.setVpcId(vpcId);
        RouteTableAssociation assocC = new RouteTableAssociation();
        assocC.setSubnetId(subnetC);
        customTableC.setAssociations(List.of(assocC));

        List<RouteTable> allTables = List.of(mainTable, customTableA, customTableC);

        // Cluster has subnets A and B. Subnet A matches customTableA. Subnet B has no explicit table,
        // so it falls back to mainTable. customTableC belongs to subnet C and must NOT be included.
        List<RouteTable> result = EksVpcRouteProgramming.findApplicableRouteTables(
                vpcId, List.of(subnetA, subnetB), allTables);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(rt -> "rtb-sub-a".equals(rt.getRouteTableId())));
        assertTrue(result.stream().anyMatch(rt -> "rtb-main".equals(rt.getRouteTableId())));
        assertFalse(result.stream().anyMatch(rt -> "rtb-sub-c".equals(rt.getRouteTableId())));
    }

    @Test
    void findApplicableRouteTablesFallsBackToMainWhenClusterHasNoSubnets() {
        String vpcId = "vpc-test";
        RouteTable mainTable = new RouteTable();
        mainTable.setRouteTableId("rtb-main");
        mainTable.setVpcId(vpcId);
        RouteTableAssociation mainAssoc = new RouteTableAssociation();
        mainAssoc.setMain(true);
        mainTable.setAssociations(List.of(mainAssoc));

        RouteTable otherTable = new RouteTable();
        otherTable.setRouteTableId("rtb-other");
        otherTable.setVpcId(vpcId);
        RouteTableAssociation otherAssoc = new RouteTableAssociation();
        otherAssoc.setSubnetId("subnet-other");
        otherTable.setAssociations(List.of(otherAssoc));

        List<RouteTable> result = EksVpcRouteProgramming.findApplicableRouteTables(
                vpcId, List.of(), List.of(mainTable, otherTable));

        assertEquals(1, result.size());
        assertEquals("rtb-main", result.get(0).getRouteTableId());
    }

    @Test
    void resolveProgrammableRoutesResolvesInstancesAndEnisAndSkipsDefaultAndUnsupported() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        // Mock EC2 instance
        Instance instance = new Instance();
        instance.setInstanceId("i-11111111");
        instance.setContainerBridgeIp("172.18.0.10");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-11111111"))).thenReturn(instance);

        // Mock EC2 network interface attached to instance
        Instance eniInstance = new Instance();
        eniInstance.setInstanceId("i-22222222");
        eniInstance.setContainerBridgeIp("172.18.0.20");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-22222222"))).thenReturn(eniInstance);

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-22222222");
        eni.setPrivateIpAddress("172.18.0.20");
        NetworkInterfaceAttachment attachment = new NetworkInterfaceAttachment();
        attachment.setInstanceId("i-22222222");
        attachment.setStatus("attached");
        eni.setAttachment(attachment);

        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));
        when(ec2.describeNetworkInterfaces(anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");

        // 1. Instance route (programmable)
        Route r1 = new Route("10.100.0.0/16", null, "CreateRoute");
        r1.setInstanceId("i-11111111");

        // 2. ENI route (programmable)
        Route r2 = new Route("10.200.0.0/16", null, "CreateRoute");
        r2.setNetworkInterfaceId("eni-22222222");

        // 3. Default route (must be skipped to preserve container egress)
        Route rDefault = new Route("0.0.0.0/0", null, "CreateRoute");
        rDefault.setInstanceId("i-11111111");

        // 4. Prefix list route (skipped)
        Route rPrefix = new Route(null, null, "CreateRoute");
        rPrefix.setDestinationPrefixListId("pl-12345");
        rPrefix.setInstanceId("i-11111111");

        // 5. Internet gateway route (skipped)
        Route rIgw = new Route("10.50.0.0/16", "igw-12345", "CreateRoute");

        // 6. Peering connection route (skipped)
        Route rPcx = new Route("10.60.0.0/16", null, "CreateRoute");
        rPcx.setVpcPeeringConnectionId("pcx-12345");

        rt.setRoutes(List.of(r1, r2, rDefault, rPrefix, rIgw, rPcx));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> "10.100.0.0/16".equals(e.destinationCidrBlock()) && "172.18.0.10".equals(e.gatewayIp())));
        assertTrue(entries.stream().anyMatch(e -> "10.200.0.0/16".equals(e.destinationCidrBlock()) && "172.18.0.20".equals(e.gatewayIp())));
    }

    @Test
    void resolveProgrammableRoutesLooksEnisUpInTheRegionItIsGiven() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setAccountId("123456789012");
        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rEni = new Route("10.200.0.0/16", null, "CreateRoute");
        rEni.setNetworkInterfaceId("eni-1");
        rt.setRoutes(List.of(rEni));

        EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "cn-north-1", List.of(rt), ec2);

        verify(ec2).describeNetworkInterfaces(eq("123456789012"), eq("cn-north-1"), eq(List.of("eni-1")), any());
    }

    @Test
    void resolveProgrammableRoutesSkipsUnattachedEni() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        // Unattached ENI (has private IP, but no attachment)
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-unattached");
        eni.setPrivateIpAddress("172.18.0.99");
        eni.setStatus("available");

        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rEni = new Route("10.200.0.0/16", null, "CreateRoute");
        rEni.setNetworkInterfaceId("eni-unattached");
        rt.setRoutes(List.of(rEni));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertTrue(entries.isEmpty(), "Unattached ENI must not be resolved as a route gateway");
    }

    @Test
    void resolveProgrammableRoutesContinuesWhenEniNotFound() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        Instance instance = new Instance();
        instance.setInstanceId("i-valid");
        instance.setContainerBridgeIp("172.18.0.50");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-valid"))).thenReturn(instance);

        // ENI lookup throws AwsException (deleted ENI)
        when(ec2.describeNetworkInterfaces(anyString(), anyString(), eq(List.of("eni-missing")), any()))
                .thenThrow(new AwsException("InvalidNetworkInterfaceID.NotFound", "ENI not found", 400));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rMissing = new Route("10.200.0.0/16", null, "CreateRoute");
        rMissing.setNetworkInterfaceId("eni-missing");
        Route rValid = new Route("10.201.0.0/16", null, "CreateRoute");
        rValid.setInstanceId("i-valid");
        rt.setRoutes(List.of(rMissing, rValid));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertEquals(1, entries.size(), "Valid instance route must still resolve despite missing ENI");
        assertEquals("10.201.0.0/16", entries.get(0).destinationCidrBlock());
        assertEquals("172.18.0.50", entries.get(0).gatewayIp());
    }

    @Test
    void resolveEniGatewayPrefersEniPrivateIpWhenVpcNetworkActive() {
        Ec2Service ec2 = mock(Ec2Service.class);
        when(ec2.isVpcNetworkEnabled()).thenReturn(true);
        when(ec2.isSecurityGroupEnforcementEnabled()).thenReturn(false);

        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        Instance eniInstance = new Instance();
        eniInstance.setInstanceId("i-22222222");
        eniInstance.setContainerBridgeIp("172.17.0.5");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-22222222"))).thenReturn(eniInstance);

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-22222222");
        eni.setPrivateIpAddress("10.0.1.20");
        NetworkInterfaceAttachment attachment = new NetworkInterfaceAttachment();
        attachment.setInstanceId("i-22222222");
        attachment.setStatus("attached");
        eni.setAttachment(attachment);

        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rEni = new Route("10.200.0.0/16", null, "CreateRoute");
        rEni.setNetworkInterfaceId("eni-22222222");
        rt.setRoutes(List.of(rEni));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertEquals(1, entries.size());
        assertEquals("10.0.1.20", entries.get(0).gatewayIp());
    }

    @Test
    void resolveEniGatewayFallsBackToInstanceGatewayWhenSecurityGroupEnforcementEnabled() {
        Ec2Service ec2 = mock(Ec2Service.class);
        when(ec2.isVpcNetworkEnabled()).thenReturn(true);
        when(ec2.isSecurityGroupEnforcementEnabled()).thenReturn(true);

        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        Instance eniInstance = new Instance();
        eniInstance.setInstanceId("i-22222222");
        eniInstance.setContainerBridgeIp("172.18.0.20");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-22222222"))).thenReturn(eniInstance);

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-22222222");
        eni.setPrivateIpAddress("10.0.1.20");
        NetworkInterfaceAttachment attachment = new NetworkInterfaceAttachment();
        attachment.setInstanceId("i-22222222");
        attachment.setStatus("attached");
        eni.setAttachment(attachment);

        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rEni = new Route("10.200.0.0/16", null, "CreateRoute");
        rEni.setNetworkInterfaceId("eni-22222222");
        rt.setRoutes(List.of(rEni));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertEquals(1, entries.size());
        assertEquals("172.18.0.20", entries.get(0).gatewayIp(),
                "Must use instance gateway IP instead of unreachable ENI private IP when security group enforcement is active");
    }

    @Test
    void resolveEniGatewayFallsBackToInstanceGatewayWhenVpcNetworkDisabled() {
        Ec2Service ec2 = mock(Ec2Service.class);
        when(ec2.isVpcNetworkEnabled()).thenReturn(false);

        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");

        Instance eniInstance = new Instance();
        eniInstance.setInstanceId("i-22222222");
        eniInstance.setContainerBridgeIp("172.17.0.5");
        when(ec2.findInstanceById(eq("123456789012"), eq("i-22222222"))).thenReturn(eniInstance);

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-22222222");
        eni.setPrivateIpAddress("10.0.1.20");
        NetworkInterfaceAttachment attachment = new NetworkInterfaceAttachment();
        attachment.setInstanceId("i-22222222");
        attachment.setStatus("attached");
        eni.setAttachment(attachment);

        when(ec2.describeNetworkInterfaces(anyString(), anyString(), anyList(), any()))
                .thenReturn(new NetworkInterfaceListResult(List.of(eni), null));

        RouteTable rt = new RouteTable();
        rt.setRouteTableId("rtb-1");
        Route rEni = new Route("10.200.0.0/16", null, "CreateRoute");
        rEni.setNetworkInterfaceId("eni-22222222");
        rt.setRoutes(List.of(rEni));

        List<EksVpcRouteProgramming.VpcRouteEntry> entries =
                EksVpcRouteProgramming.resolveProgrammableRoutes(cluster, "us-east-1", List.of(rt), ec2);

        assertEquals(1, entries.size());
        assertEquals("172.17.0.5", entries.get(0).gatewayIp(),
                "Must use instance gateway IP when VPC networking is disabled");
    }
}
