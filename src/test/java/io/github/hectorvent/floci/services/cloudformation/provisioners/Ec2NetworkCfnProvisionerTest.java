package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import io.github.hectorvent.floci.core.common.AwsException;
import java.util.Map;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EC2 networking provisioner in isolation: one mocked service. Every case asserts the exact
 * physical id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type still reports
 * CREATE_COMPLETE through the dispatcher's stub arm.
 */
class Ec2NetworkCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String VPC_ID = "vpc-0123456789abcdef0";
    private static final String SUBNET_ID = "subnet-0123456789abcdef0";
    private static final String RTB_ID = "rtb-0123456789abcdef0";
    private static final String IGW_ID = "igw-0123456789abcdef0";
    private static final String NAT_ID = "nat-0123456789abcdef0";
    private static final String ASSOC_ID = "rtbassoc-0123456789abcdef0";
    private static final String ALLOC_ID = "eipalloc-0123456789abcdef0";
    private static final String PUBLIC_IP = "54.0.0.1";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2NetworkCfnProvisioner provisioner = new Ec2NetworkCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Subnet subnet() {
        Subnet s = new Subnet();
        s.setSubnetId(SUBNET_ID);
        s.setVpcId(VPC_ID);
        s.setCidrBlock("10.0.1.0/24");
        s.setAvailabilityZone("us-east-1a");
        return s;
    }

    @Test
    void subnetSetsItsIdAndTheThreeAttributesAndAppliesMapPublicIpOnLaunch() {
        when(ec2.createSubnet(REGION, VPC_ID, "10.0.1.0/24", "us-east-1a")).thenReturn(subnet());
        ObjectNode props = mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24")
                .put("AvailabilityZone", "us-east-1a").put("MapPublicIpOnLaunch", true);
        StackResource r = resource("AWS::EC2::Subnet", "Subnet");

        provisioner.provision(r, props, ctx());

        assertEquals(SUBNET_ID, r.getPhysicalId());
        assertEquals(Set.of("SubnetId", "VpcId", "AvailabilityZone"), r.getAttributes().keySet());
        assertEquals("us-east-1a", r.getAttributes().get("AvailabilityZone"));
        verify(ec2).modifySubnetAttribute(REGION, SUBNET_ID, "mapPublicIpOnLaunch", "true");
    }

    @Test
    void subnetWithoutMapPublicIpOnLaunchLeavesTheAttributeAlone() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());

        provisioner.provision(resource("AWS::EC2::Subnet", "Subnet"),
                mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), ctx());

        verify(ec2, never()).modifySubnetAttribute(any(), any(), any(), any());
    }

    @Test
    void internetGatewaySetsItsIdAttribute() {
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(IGW_ID);
        when(ec2.createInternetGateway(REGION)).thenReturn(igw);
        StackResource r = resource("AWS::EC2::InternetGateway", "Igw");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals(IGW_ID, r.getPhysicalId());
        assertEquals(Set.of("InternetGatewayId"), r.getAttributes().keySet());
    }

    @Test
    void routeTableSetsItsIdAttribute() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        when(ec2.createRouteTable(REGION, VPC_ID)).thenReturn(rt);
        StackResource r = resource("AWS::EC2::RouteTable", "Rtb");

        provisioner.provision(r, mapper.createObjectNode().put("VpcId", VPC_ID), ctx());

        assertEquals(RTB_ID, r.getPhysicalId());
        assertEquals(Set.of("RouteTableId"), r.getAttributes().keySet());
    }

    @Test
    void associationSetsTheSchemasIdAttribute() {
        RouteTableAssociation assoc = new RouteTableAssociation();
        assoc.setRouteTableAssociationId(ASSOC_ID);
        when(ec2.associateRouteTable(REGION, RTB_ID, SUBNET_ID)).thenReturn(assoc);
        StackResource r = resource("AWS::EC2::SubnetRouteTableAssociation", "Assoc");

        provisioner.provision(r, mapper.createObjectNode().put("RouteTableId", RTB_ID).put("SubnetId", SUBNET_ID), ctx());

        assertEquals(ASSOC_ID, r.getPhysicalId());
        assertEquals(Set.of("Id"), r.getAttributes().keySet());
    }

    @Test
    void routePassesEveryTargetAndExposesTheDestinationAsCidrBlock() {
        StackResource r = resource("AWS::EC2::Route", "Default");
        ObjectNode props = mapper.createObjectNode().put("RouteTableId", RTB_ID)
                .put("DestinationCidrBlock", "0.0.0.0/0").put("GatewayId", IGW_ID);

        provisioner.provision(r, props, ctx());

        verify(ec2).createRoute(REGION, RTB_ID, "0.0.0.0/0", null, null, IGW_ID, null, null, null);
        assertEquals(RTB_ID + "|0.0.0.0/0", r.getPhysicalId(), "Ref is the registry primary identifier");
        assertEquals(Set.of("CidrBlock"), r.getAttributes().keySet());
        assertEquals("0.0.0.0/0", r.getAttributes().get("CidrBlock"));
    }

    @Test
    void routeToAPrefixListExposesThatAsCidrBlock() {
        StackResource r = resource("AWS::EC2::Route", "S3Route");
        ObjectNode props = mapper.createObjectNode().put("RouteTableId", RTB_ID)
                .put("DestinationPrefixListId", "pl-63a5400a").put("NatGatewayId", NAT_ID);

        provisioner.provision(r, props, ctx());

        verify(ec2).createRoute(REGION, RTB_ID, null, null, "pl-63a5400a", null, NAT_ID, null, null);
        assertEquals("pl-63a5400a", r.getAttributes().get("CidrBlock"));
    }

    @Test
    void natGatewayIsCreatedPublicWithNoTags() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        when(ec2.createNatGateway(REGION, SUBNET_ID, ALLOC_ID, "public", List.of())).thenReturn(nat);
        StackResource r = resource("AWS::EC2::NatGateway", "Nat");

        provisioner.provision(r, mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("AllocationId", ALLOC_ID), ctx());

        assertEquals(NAT_ID, r.getPhysicalId());
        assertEquals(Set.of("NatGatewayId"), r.getAttributes().keySet());
    }

    @Test
    void eipRefIsThePublicIpAndAllocationIdIsAnAttribute() {
        Address addr = new Address();
        addr.setAllocationId(ALLOC_ID);
        addr.setPublicIp(PUBLIC_IP);
        when(ec2.allocateAddress(REGION)).thenReturn(addr);
        StackResource r = resource("AWS::EC2::EIP", "Eip");

        provisioner.provision(r, mapper.createObjectNode().put("Domain", "vpc"), ctx());

        assertEquals(PUBLIC_IP, r.getPhysicalId());
        assertEquals(Set.of("AllocationId", "PublicIp"), r.getAttributes().keySet());
        assertEquals(ALLOC_ID, r.getAttributes().get("AllocationId"));
    }

    @Test
    void deletesUseEachTypesOwnApiAndTolerateOnlyItsNotFoundCode() {
        provisioner.delete("AWS::EC2::Subnet", SUBNET_ID, REGION);
        provisioner.delete("AWS::EC2::InternetGateway", IGW_ID, REGION);
        provisioner.delete("AWS::EC2::RouteTable", RTB_ID, REGION);
        provisioner.delete("AWS::EC2::NatGateway", NAT_ID, REGION);
        provisioner.delete("AWS::EC2::SubnetRouteTableAssociation", ASSOC_ID, REGION);
        verify(ec2).deleteSubnet(REGION, SUBNET_ID);
        verify(ec2).deleteInternetGateway(REGION, IGW_ID);
        verify(ec2).deleteRouteTable(REGION, RTB_ID);
        verify(ec2).deleteNatGateway(REGION, NAT_ID);
        verify(ec2).disassociateRouteTable(REGION, ASSOC_ID);

        doThrow(new AwsException("InvalidSubnetID.NotFound", "gone", 400)).when(ec2).deleteSubnet(REGION, "subnet-gone");
        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::Subnet", "subnet-gone", REGION));
        doThrow(new AwsException("DependencyViolation", "has dependencies", 400)).when(ec2).deleteSubnet(REGION, "subnet-busy");
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete("AWS::EC2::Subnet", "subnet-busy", REGION));
        assertEquals("DependencyViolation", e.getErrorCode());
    }

    @Test
    void routeDeleteParsesTheTableAndDestinationFromItsId() {
        provisioner.delete("AWS::EC2::Route", RTB_ID + "|0.0.0.0/0", REGION);
        provisioner.delete("AWS::EC2::Route", RTB_ID + "|::/0", REGION);
        provisioner.delete("AWS::EC2::Route", RTB_ID + "|pl-63a5400a", REGION);

        verify(ec2).deleteRoute(REGION, RTB_ID, "0.0.0.0/0", null, null);
        verify(ec2).deleteRoute(REGION, RTB_ID, null, "::/0", null);
        verify(ec2).deleteRoute(REGION, RTB_ID, null, null, "pl-63a5400a");
    }

    @Test
    void routeDeleteToleratesAMissingRouteOrTableAndSkipsALegacyGeneratedId() {
        doThrow(new AwsException("InvalidRouteTableID.NotFound", "gone", 400))
                .when(ec2).deleteRoute(REGION, "rtb-gone", "10.0.0.0/8", null, null);

        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::Route", "rtb-gone|10.0.0.0/8", REGION));
        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::Route", "Default-1a2b3c4d", REGION));
        verify(ec2, never()).deleteRoute(eq(REGION), eq("Default-1a2b3c4d"), any(), any(), any());
    }

    @Test
    void eipReleaseUsesTheRecordedAllocationIdOrLooksTheAddressUpByIp() {
        StackResource r = resource("AWS::EC2::EIP", "Eip");
        r.setPhysicalId(PUBLIC_IP);
        r.getAttributes().put("AllocationId", ALLOC_ID);
        provisioner.delete(r, REGION);
        verify(ec2).releaseAddress(REGION, ALLOC_ID);
        verify(ec2, never()).describeAddresses(any(), any(), any());

        Address addr = new Address();
        addr.setAllocationId("eipalloc-byip");
        addr.setPublicIp("54.0.0.2");
        when(ec2.describeAddresses(REGION, List.of(), Map.of("public-ip", List.of("54.0.0.2")))).thenReturn(List.of(addr));
        provisioner.delete("AWS::EC2::EIP", "54.0.0.2", REGION);
        verify(ec2).releaseAddress(REGION, "eipalloc-byip");

        when(ec2.describeAddresses(REGION, List.of(), Map.of("public-ip", List.of("54.0.0.3")))).thenReturn(List.of());
        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::EIP", "54.0.0.3", REGION));
    }

    private static StackResource prior(String type, String logicalId, String physicalId, Map<String, String> attributes) {
        StackResource r = resource(type, logicalId);
        r.setPhysicalId(physicalId);
        r.getAttributes().putAll(attributes);
        return r;
    }

    @Test
    void anUnchangedSubnetIsKeptAndOnlyMapPublicIpOnLaunchIsApplied() {
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(subnet()));
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID, "VpcId", VPC_ID, "AvailabilityZone", "us-east-1a"));
        ObjectNode props = mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24")
                .put("AvailabilityZone", "us-east-1a").put("MapPublicIpOnLaunch", false);

        provisioner.provision(r, props, ctx(SUBNET_ID));

        verify(ec2, never()).createSubnet(any(), any(), any(), any());
        verify(ec2).modifySubnetAttribute(REGION, SUBNET_ID, "mapPublicIpOnLaunch", "false");
        assertEquals(SUBNET_ID, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertTrue(provisioner.rollbackUpdate(r), "the prior MapPublicIpOnLaunch is restored from the snapshot");
        verify(ec2).modifySubnetAttribute(REGION, SUBNET_ID, "mapPublicIpOnLaunch", "false");
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aSubnetWithAChangedCidrIsReplacedAndThePriorOneIsOwedToCleanup() {
        Subnet current = subnet();
        current.setCidrBlock("10.0.1.0/24");
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(current));
        Subnet replacement = subnet();
        replacement.setSubnetId("subnet-replacement");
        when(ec2.createSubnet(REGION, VPC_ID, "10.0.2.0/24", "us-east-1a")).thenReturn(replacement);
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));
        ObjectNode props = mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.2.0/24").put("AvailabilityZone", "us-east-1a");

        provisioner.provision(r, props, ctx(SUBNET_ID));

        assertEquals("subnet-replacement", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(SUBNET_ID, provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(ec2).deleteSubnet(REGION, SUBNET_ID);
    }

    @Test
    void aSubnetGoneOutOfBandIsCreatedAnew() {
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of()))
                .thenThrow(new AwsException("InvalidSubnetID.NotFound", "gone", 400));
        when(ec2.createSubnet(REGION, VPC_ID, "10.0.1.0/24", null)).thenReturn(subnet());
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of());

        provisioner.provision(r, mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), ctx(SUBNET_ID));

        assertEquals(SUBNET_ID, r.getPhysicalId());
    }

    @Test
    void anInternetGatewayAndARouteTableInTheSameVpcAreKeptAndRollBackAsComplete() {
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(IGW_ID);
        when(ec2.describeInternetGateways(REGION, List.of(IGW_ID), Map.of())).thenReturn(List.of(igw));
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        StackResource igwResource = prior("AWS::EC2::InternetGateway", "Igw", IGW_ID, Map.of("InternetGatewayId", IGW_ID));
        StackResource rtResource = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        provisioner.provision(igwResource, mapper.createObjectNode(), ctx(IGW_ID));
        provisioner.provision(rtResource, mapper.createObjectNode().put("VpcId", VPC_ID), ctx(RTB_ID));

        verify(ec2, never()).createInternetGateway(any());
        verify(ec2, never()).createRouteTable(any(), any());
        assertTrue(provisioner.rollbackUpdate(igwResource));
        assertTrue(provisioner.rollbackUpdate(rtResource));
    }

    @Test
    void aRouteTableMovedToAnotherVpcIsReplaced() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        RouteTable replacement = new RouteTable();
        replacement.setRouteTableId("rtb-replacement");
        when(ec2.createRouteTable(REGION, "vpc-other")).thenReturn(replacement);
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        provisioner.provision(r, mapper.createObjectNode().put("VpcId", "vpc-other"), ctx(RTB_ID));

        assertEquals("rtb-replacement", r.getPhysicalId());
        assertEquals(RTB_ID, provisioner.updateCleanupPhysicalId(r));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(ec2).deleteRouteTable(REGION, "rtb-replacement");
        assertEquals(RTB_ID, r.getPhysicalId());
    }

    @Test
    void aNatGatewayWithTheSameSubnetAndAllocationIsKeptAndAChangedSubnetReplacesIt() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        nat.setSubnetId(SUBNET_ID);
        nat.setAllocationId(ALLOC_ID);
        when(ec2.describeNatGateways(REGION, List.of(NAT_ID), Map.of())).thenReturn(List.of(nat));
        StackResource same = prior("AWS::EC2::NatGateway", "Nat", NAT_ID, Map.of("NatGatewayId", NAT_ID));
        provisioner.provision(same, mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("AllocationId", ALLOC_ID), ctx(NAT_ID));
        verify(ec2, never()).createNatGateway(any(), any(), any(), any(), any());
        assertEquals(NAT_ID, same.getPhysicalId());

        NatGateway replacement = new NatGateway();
        replacement.setNatGatewayId("nat-replacement");
        when(ec2.createNatGateway(REGION, "subnet-other", ALLOC_ID, "public", List.of())).thenReturn(replacement);
        StackResource moved = prior("AWS::EC2::NatGateway", "Nat", NAT_ID, Map.of("NatGatewayId", NAT_ID));
        provisioner.provision(moved, mapper.createObjectNode().put("SubnetId", "subnet-other").put("AllocationId", ALLOC_ID), ctx(NAT_ID));
        assertEquals("nat-replacement", moved.getPhysicalId());
        assertEquals(NAT_ID, provisioner.updateCleanupPhysicalId(moved));
    }

    @Test
    void anAllocatedEipIsKeptAcrossUpdates() {
        Address addr = new Address();
        addr.setAllocationId(ALLOC_ID);
        addr.setPublicIp(PUBLIC_IP);
        when(ec2.describeAddresses(REGION, List.of(ALLOC_ID), Map.of())).thenReturn(List.of(addr));
        StackResource r = prior("AWS::EC2::EIP", "Eip", PUBLIC_IP, Map.of("AllocationId", ALLOC_ID, "PublicIp", PUBLIC_IP));

        provisioner.provision(r, mapper.createObjectNode().put("Domain", "vpc"), ctx(PUBLIC_IP));

        verify(ec2, never()).allocateAddress(any());
        assertEquals(PUBLIC_IP, r.getPhysicalId());
    }

    @Test
    void anAssociationWithTheSamePairIsKeptAndAnotherSubnetReplacesIt() {
        RouteTableAssociation assoc = new RouteTableAssociation();
        assoc.setRouteTableAssociationId(ASSOC_ID);
        assoc.setRouteTableId(RTB_ID);
        assoc.setSubnetId(SUBNET_ID);
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setAssociations(List.of(assoc));
        when(ec2.describeRouteTables(REGION, List.of(), Map.of("association.route-table-association-id", List.of(ASSOC_ID))))
                .thenReturn(List.of(rt));
        StackResource same = prior("AWS::EC2::SubnetRouteTableAssociation", "Assoc", ASSOC_ID, Map.of("Id", ASSOC_ID));
        provisioner.provision(same, mapper.createObjectNode().put("RouteTableId", RTB_ID).put("SubnetId", SUBNET_ID), ctx(ASSOC_ID));
        verify(ec2, never()).associateRouteTable(any(), any(), any());

        RouteTableAssociation replacement = new RouteTableAssociation();
        replacement.setRouteTableAssociationId("rtbassoc-replacement");
        when(ec2.associateRouteTable(REGION, RTB_ID, "subnet-other")).thenReturn(replacement);
        StackResource moved = prior("AWS::EC2::SubnetRouteTableAssociation", "Assoc", ASSOC_ID, Map.of("Id", ASSOC_ID));
        provisioner.provision(moved, mapper.createObjectNode().put("RouteTableId", RTB_ID).put("SubnetId", "subnet-other"), ctx(ASSOC_ID));
        assertEquals("rtbassoc-replacement", moved.getPhysicalId());
        assertEquals(ASSOC_ID, provisioner.updateCleanupPhysicalId(moved));
        provisioner.completeUpdate(moved);
        verify(ec2).disassociateRouteTable(REGION, ASSOC_ID);
    }

    @Test
    void aRouteWithTheSameTableAndDestinationChangesItsTargetInPlace() {
        Route route = new Route();
        route.setDestinationCidrBlock("0.0.0.0/0");
        route.setGatewayId(IGW_ID);
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setRoutes(List.of(route));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        StackResource r = prior("AWS::EC2::Route", "Default", RTB_ID + "|0.0.0.0/0", Map.of("CidrBlock", "0.0.0.0/0"));
        ObjectNode props = mapper.createObjectNode().put("RouteTableId", RTB_ID).put("DestinationCidrBlock", "0.0.0.0/0").put("NatGatewayId", NAT_ID);

        provisioner.provision(r, props, ctx(RTB_ID + "|0.0.0.0/0"));

        verify(ec2).replaceRoute(REGION, RTB_ID, "0.0.0.0/0", null, null, null, NAT_ID, null);
        verify(ec2, never()).createRoute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(RTB_ID + "|0.0.0.0/0", r.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(r), "the prior target is restored from the snapshot");
        verify(ec2).replaceRoute(REGION, RTB_ID, "0.0.0.0/0", null, null, IGW_ID, null, null);
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aRouteWithAChangedDestinationIsANewRouteAndThePriorOneIsOwedToCleanup() {
        StackResource r = prior("AWS::EC2::Route", "Default", RTB_ID + "|0.0.0.0/0", Map.of("CidrBlock", "0.0.0.0/0"));
        ObjectNode props = mapper.createObjectNode().put("RouteTableId", RTB_ID).put("DestinationCidrBlock", "10.1.0.0/16").put("GatewayId", IGW_ID);

        provisioner.provision(r, props, ctx(RTB_ID + "|0.0.0.0/0"));

        verify(ec2).createRoute(REGION, RTB_ID, "10.1.0.0/16", null, null, IGW_ID, null, null, null);
        assertEquals(RTB_ID + "|10.1.0.0/16", r.getPhysicalId());
        assertEquals(RTB_ID + "|0.0.0.0/0", provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(ec2).deleteRoute(REGION, RTB_ID, "0.0.0.0/0", null, null);
    }

    /** The EC2 Tag model has no equals, so tag lists are compared as key=value pairs. */
    private static List<String> pairs(List<Tag> tags) {
        return tags.stream().map(t -> t.getKey() + "=" + t.getValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> tagsWritten(String resourceId) {
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(ec2).createTags(eq(REGION), eq(List.of(resourceId)), tags.capture());
        return pairs(tags.getValue());
    }

    private static ObjectNode withTags(ObjectNode props, String... keyValues) {
        var tags = props.putArray("Tags");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            tags.addObject().put("Key", keyValues[i]).put("Value", keyValues[i + 1]);
        }
        return props;
    }

    @Test
    void tagsAreWrittenOnCreateForEveryTaggableType() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        when(ec2.createRouteTable(REGION, VPC_ID)).thenReturn(rt);
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(IGW_ID);
        when(ec2.createInternetGateway(REGION)).thenReturn(igw);
        Address addr = new Address();
        addr.setAllocationId(ALLOC_ID);
        addr.setPublicIp(PUBLIC_IP);
        when(ec2.allocateAddress(REGION)).thenReturn(addr);

        provisioner.provision(resource("AWS::EC2::Subnet", "Subnet"),
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "Name", "public"), ctx());
        provisioner.provision(resource("AWS::EC2::RouteTable", "Rtb"),
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "Name", "public-rt"), ctx());
        provisioner.provision(resource("AWS::EC2::InternetGateway", "Igw"), withTags(mapper.createObjectNode(), "Name", "igw"), ctx());
        provisioner.provision(resource("AWS::EC2::EIP", "Eip"), withTags(mapper.createObjectNode(), "Name", "nat-ip"), ctx());

        assertEquals(List.of("Name=public"), tagsWritten(SUBNET_ID));
        assertEquals(List.of("Name=public-rt"), tagsWritten(RTB_ID));
        assertEquals(List.of("Name=igw"), tagsWritten(IGW_ID));
        assertEquals(List.of("Name=nat-ip"), tagsWritten(ALLOC_ID));
        verify(ec2, never()).deleteTags(any(), any(), any());
    }

    @Test
    void aPrivateNatGatewayNeedsNoAllocationAndAPublicOneRequiresIt() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        when(ec2.createNatGateway(eq(REGION), eq(SUBNET_ID), isNull(), eq("private"), any())).thenReturn(nat);
        StackResource r = resource("AWS::EC2::NatGateway", "Nat");

        provisioner.provision(r, mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("ConnectivityType", "private"), ctx());
        assertEquals(NAT_ID, r.getPhysicalId());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource("AWS::EC2::NatGateway", "Nat2"),
                mapper.createObjectNode().put("SubnetId", SUBNET_ID), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        AwsException e2 = assertThrows(AwsException.class, () -> provisioner.provision(resource("AWS::EC2::NatGateway", "Nat3"),
                mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("ConnectivityType", "private").put("AllocationId", ALLOC_ID), ctx()));
        assertEquals("ValidationError", e2.getErrorCode());
    }

    @Test
    void aNatGatewayWhoseConnectivityTypeChangedIsReplaced() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        nat.setSubnetId(SUBNET_ID);
        nat.setAllocationId(ALLOC_ID);
        nat.setConnectivityType("public");
        when(ec2.describeNatGateways(REGION, List.of(NAT_ID), Map.of())).thenReturn(List.of(nat));
        NatGateway replacement = new NatGateway();
        replacement.setNatGatewayId("nat-private");
        when(ec2.createNatGateway(eq(REGION), eq(SUBNET_ID), isNull(), eq("private"), any())).thenReturn(replacement);
        StackResource r = prior("AWS::EC2::NatGateway", "Nat", NAT_ID, Map.of("NatGatewayId", NAT_ID));

        provisioner.provision(r, mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("ConnectivityType", "private"), ctx(NAT_ID));

        assertEquals("nat-private", r.getPhysicalId());
        assertEquals(NAT_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void natGatewayTagsGoThroughItsCreateCall() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        when(ec2.createNatGateway(eq(REGION), eq(SUBNET_ID), eq(ALLOC_ID), eq("public"), any())).thenReturn(nat);

        provisioner.provision(resource("AWS::EC2::NatGateway", "Nat"),
                withTags(mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("AllocationId", ALLOC_ID), "Name", "nat"), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(ec2).createNatGateway(eq(REGION), eq(SUBNET_ID), eq(ALLOC_ID), eq("public"), tags.capture());
        assertEquals(List.of("Name=nat"), pairs(tags.getValue()));
        verify(ec2, never()).createTags(any(), any(), any());
    }

    @Test
    void aReusedSubnetDropsTheTagKeysTheTemplateRemovedAndWritesTheRest() {
        Subnet current = subnet();
        current.setTags(List.of(new Tag("Name", "old"), new Tag("team", "net")));
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(current));
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));
        ObjectNode props = withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "Name", "new");

        provisioner.provision(r, props, ctx(SUBNET_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2).deleteTags(eq(REGION), eq(List.of(SUBNET_ID)), removed.capture());
        assertEquals(List.of("team=null"), pairs(removed.getValue()));
        assertEquals(List.of("Name=new"), tagsWritten(SUBNET_ID));
    }

    @Test
    void aTagChangeOnAReusedEntityIsRolledBackToThePriorTags() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        rt.setTags(List.of(new Tag("Name", "old"), new Tag("team", "net")));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        provisioner.provision(r, withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "Name", "new", "env", "test"), ctx(RTB_ID));
        assertTrue(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR), "the prior tags are snapshotted");

        assertTrue(provisioner.rollbackUpdate(r));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).deleteTags(eq(REGION), eq(List.of(RTB_ID)), removed.capture());
        assertEquals(List.of("env=null"), pairs(removed.getAllValues().get(1)), "the key the update added is removed");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> written = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).createTags(eq(REGION), eq(List.of(RTB_ID)), written.capture());
        assertEquals(List.of("Name=old", "team=net"), pairs(written.getAllValues().get(1)), "the prior tags are written back");
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void anUnchangedReuseLeavesNoSnapshotAndRollsBackAsComplete() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        rt.setTags(List.of(new Tag("Name", "same")));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        provisioner.provision(r, withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "Name", "same"), ctx(RTB_ID));

        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(ec2, never()).deleteTags(any(), any(), any());
    }

    @Test
    void completingOrClearingAnUpdateDropsTheSnapshot() {
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR, "{}"));
        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        r.getAttributes().put(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR, "{}");
        provisioner.clearUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aTemplateWithoutTagsLeavesExistingTagsAlone() {
        Subnet current = subnet();
        current.setTags(List.of(new Tag("Name", "kept")));
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(current));
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));

        provisioner.provision(r, mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), ctx(SUBNET_ID));

        verify(ec2, never()).deleteTags(any(), any(), any());
        verify(ec2, never()).createTags(any(), any(), any());
    }

    @Test
    void requiredPropertiesAreValidatedBeforeAnyServiceCall() {
        record Case(String type, ObjectNode props, String expected) { }
        List<Case> cases = List.of(
                new Case("AWS::EC2::Subnet", mapper.createObjectNode().put("CidrBlock", "10.0.1.0/24"), "AWS::EC2::Subnet requires VpcId"),
                new Case("AWS::EC2::RouteTable", mapper.createObjectNode(), "AWS::EC2::RouteTable requires VpcId"),
                new Case("AWS::EC2::SubnetRouteTableAssociation", mapper.createObjectNode().put("SubnetId", SUBNET_ID),
                        "AWS::EC2::SubnetRouteTableAssociation requires RouteTableId"),
                new Case("AWS::EC2::SubnetRouteTableAssociation", mapper.createObjectNode().put("RouteTableId", RTB_ID),
                        "AWS::EC2::SubnetRouteTableAssociation requires SubnetId"),
                new Case("AWS::EC2::NatGateway", mapper.createObjectNode().put("AllocationId", ALLOC_ID), "AWS::EC2::NatGateway requires SubnetId"),
                new Case("AWS::EC2::Route", mapper.createObjectNode().put("DestinationCidrBlock", "0.0.0.0/0"), "AWS::EC2::Route requires RouteTableId"),
                new Case("AWS::EC2::Route", mapper.createObjectNode().put("RouteTableId", RTB_ID),
                        "AWS::EC2::Route requires exactly one of DestinationCidrBlock, DestinationIpv6CidrBlock or DestinationPrefixListId"),
                new Case("AWS::EC2::Route", mapper.createObjectNode().put("RouteTableId", RTB_ID).put("DestinationCidrBlock", "0.0.0.0/0").put("DestinationIpv6CidrBlock", "::/0"),
                        "AWS::EC2::Route requires exactly one of DestinationCidrBlock, DestinationIpv6CidrBlock or DestinationPrefixListId"));
        for (Case c : cases) {
            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource(c.type(), "R"), c.props(), ctx()), c.expected());
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals(c.expected(), e.getMessage());
        }
        verify(ec2, never()).createSubnet(any(), any(), any(), any());
        verify(ec2, never()).createRouteTable(any(), any());
        verify(ec2, never()).associateRouteTable(any(), any(), any());
        verify(ec2, never()).createRoute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(ec2, never()).createNatGateway(any(), any(), any(), any(), any());
    }

    @Test
    void aNonCanonicalIpv4DestinationIsKeyedByItsCanonicalFormAndReusedAsSuch() {
        StackResource created = resource("AWS::EC2::Route", "Default");
        provisioner.provision(created, mapper.createObjectNode().put("RouteTableId", RTB_ID)
                .put("DestinationCidrBlock", "10.40.0.18/24").put("GatewayId", IGW_ID), ctx());
        assertEquals(RTB_ID + "|10.40.0.0/24", created.getPhysicalId(), "the id carries the canonical CIDR the service stores");
        assertEquals("10.40.0.0/24", created.getAttributes().get("CidrBlock"));
        verify(ec2).createRoute(REGION, RTB_ID, "10.40.0.18/24", null, null, IGW_ID, null, null, null);

        Route stored = new Route();
        stored.setDestinationCidrBlock("10.40.0.0/24");
        stored.setGatewayId(IGW_ID);
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setRoutes(List.of(stored));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        StackResource updated = prior("AWS::EC2::Route", "Default", RTB_ID + "|10.40.0.0/24", Map.of("CidrBlock", "10.40.0.0/24"));
        provisioner.provision(updated, mapper.createObjectNode().put("RouteTableId", RTB_ID)
                .put("DestinationCidrBlock", "10.40.0.18/24").put("GatewayId", IGW_ID), ctx(RTB_ID + "|10.40.0.0/24"));
        verify(ec2).replaceRoute(REGION, RTB_ID, "10.40.0.18/24", null, null, IGW_ID, null, null);
        verify(ec2, org.mockito.Mockito.times(1)).createRoute(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(RTB_ID + "|10.40.0.0/24", updated.getPhysicalId());
    }

    @Test
    void aFailedStepAfterACreateDeletesWhatWasJustCreatedAndPropagates() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        when(ec2.createRouteTable(REGION, VPC_ID)).thenReturn(rt);
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(IGW_ID);
        when(ec2.createInternetGateway(REGION)).thenReturn(igw);
        Address addr = new Address();
        addr.setAllocationId(ALLOC_ID);
        addr.setPublicIp(PUBLIC_IP);
        when(ec2.allocateAddress(REGION)).thenReturn(addr);
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());

        for (var attempt : List.of(
                Map.entry("AWS::EC2::Subnet", withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "k", "v")),
                Map.entry("AWS::EC2::RouteTable", withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "k", "v")),
                Map.entry("AWS::EC2::InternetGateway", withTags(mapper.createObjectNode(), "k", "v")),
                Map.entry("AWS::EC2::EIP", withTags(mapper.createObjectNode(), "k", "v")))) {
            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(attempt.getKey(), "R"), attempt.getValue(), ctx()), attempt.getKey());
            assertEquals("TagLimitExceeded", e.getErrorCode());
        }
        verify(ec2).deleteSubnet(REGION, SUBNET_ID);
        verify(ec2).deleteRouteTable(REGION, RTB_ID);
        verify(ec2).deleteInternetGateway(REGION, IGW_ID);
        verify(ec2).releaseAddress(REGION, ALLOC_ID);
    }

    @Test
    void aSuccessfulCreateLeavesNoRollbackOwnedMarker() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());
        StackResource r = resource("AWS::EC2::Subnet", "Subnet");

        provisioner.provision(r, withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "k", "v"), ctx());

        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aFailedUndoOfACreateLeavesTheEntityRollbackOwnedAndReportsTheReason() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());
        doThrow(new AwsException("DependencyViolation", "in use", 400)).when(ec2).deleteSubnet(REGION, SUBNET_ID);
        StackResource r = resource("AWS::EC2::Subnet", "Subnet");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "k", "v"), ctx()));

        assertEquals("TagLimitExceeded", e.getErrorCode());
        assertEquals(1, e.getSuppressed().length);
        assertEquals(SUBNET_ID, r.getPhysicalId(), "the id stays so the engine's rollback can delete it");
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains("Could not remove " + SUBNET_ID));
    }

    @Test
    void aReplacementThatCouldNotBeRemovedAfterAFailedStepIsOwedToTheNextCleanup() {
        Subnet current = subnet();
        current.setCidrBlock("10.0.1.0/24");
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(current));
        Subnet replacement = subnet();
        replacement.setSubnetId("subnet-replacement");
        when(ec2.createSubnet(REGION, VPC_ID, "10.0.2.0/24", "us-east-1a")).thenReturn(replacement);
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());
        doThrow(new AwsException("DependencyViolation", "in use", 400)).when(ec2).deleteSubnet(REGION, "subnet-replacement");
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));
        ObjectNode props = withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.2.0/24").put("AvailabilityZone", "us-east-1a"), "k", "v");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(SUBNET_ID)));

        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains("Could not remove subnet-replacement"));
        assertTrue(provisioner.hasReplacementUpdate(r), "the replacement is listed in the attempt's cleanup record");
        // The engine restores the previous resource and carries the record across (mergeFailedUpdateResourceTracking).
        StackResource previous = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));
        ReplacementCleanup.mergeDisplaced(previous, r);
        assertEquals("subnet-replacement", provisioner.updateCleanupPhysicalId(previous), "the replacement stays owed a delete");
        provisioner.completeUpdate(previous);
        verify(ec2, org.mockito.Mockito.times(2)).deleteSubnet(REGION, "subnet-replacement");
    }

    @Test
    void aFreshCreateThatCouldNotBeRemovedRecordsNoCleanupEntry() {
        when(ec2.createSubnet(eq(REGION), eq(VPC_ID), eq("10.0.1.0/24"), isNull())).thenReturn(subnet());
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());
        doThrow(new AwsException("DependencyViolation", "in use", 400)).when(ec2).deleteSubnet(REGION, SUBNET_ID);
        StackResource r = resource("AWS::EC2::Subnet", "Subnet");

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "k", "v"), ctx()));

        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR), "the engine's create rollback owns it");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aFailedRestoreOfAReusedEntityKeepsTheSnapshotAndReportsTheReason() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        rt.setTags(List.of(new Tag("Name", "old")));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        // Two distinct instances: the attempt's failure and the restore's, since a throwable cannot suppress itself.
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400))
                .doThrow(new AwsException("TagLimitExceeded", "still too many tags", 400))
                .when(ec2).createTags(eq(REGION), eq(List.of(RTB_ID)), any());
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "Name", "new"), ctx(RTB_ID)));

        assertEquals("TagLimitExceeded", e.getErrorCode());
        assertEquals(1, e.getSuppressed().length);
        assertTrue(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR), "the snapshot is kept");
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains("Could not restore " + RTB_ID));
        verify(ec2, never()).deleteRouteTable(any(), any());
    }

    @Test
    void aFailureMidWayThroughAnInPlaceTagChangePutsThePriorTagsBack() {
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setVpcId(VPC_ID);
        rt.setTags(List.of(new Tag("Name", "old"), new Tag("team", "net")));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        // deleteTags (of "team") succeeds, then the createTags of the new set throws; the restore's createTags succeeds.
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).doNothing()
                .when(ec2).createTags(eq(REGION), eq(List.of(RTB_ID)), any());
        StackResource r = prior("AWS::EC2::RouteTable", "Rtb", RTB_ID, Map.of("RouteTableId", RTB_ID));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID), "Name", "new", "env", "test"), ctx(RTB_ID)));

        assertEquals("TagLimitExceeded", e.getErrorCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> written = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).createTags(eq(REGION), eq(List.of(RTB_ID)), written.capture());
        assertEquals(List.of("Name=old", "team=net"), pairs(written.getAllValues().get(1)), "the prior tags are put back");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).deleteTags(eq(REGION), eq(List.of(RTB_ID)), removed.capture());
        assertEquals(List.of("env=null"), pairs(removed.getAllValues().get(1)), "the key the failed update added is removed again");
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR), "a successful restore reports nothing");
        verify(ec2, never()).deleteRouteTable(any(), any());
    }

    @Test
    void aFailedSubnetAttributeChangeAfterTagsPutsTheTagsBack() {
        Subnet current = subnet();
        current.setTags(List.of(new Tag("Name", "old")));
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(current));
        doThrow(new AwsException("InvalidParameterValue", "nope", 400))
                .when(ec2).modifySubnetAttribute(REGION, SUBNET_ID, "mapPublicIpOnLaunch", "true");
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));
        ObjectNode props = withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24")
                .put("MapPublicIpOnLaunch", true), "Name", "new");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(SUBNET_ID)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> written = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).createTags(eq(REGION), eq(List.of(SUBNET_ID)), written.capture());
        assertEquals(List.of("Name=old"), pairs(written.getAllValues().get(1)));
        // the snapshot recorded the prior value before the flip was attempted, so the restore writes it back
        verify(ec2).modifySubnetAttribute(REGION, SUBNET_ID, "mapPublicIpOnLaunch", "false");
        verify(ec2, org.mockito.Mockito.times(2)).modifySubnetAttribute(any(), any(), any(), any());
        verify(ec2, never()).deleteSubnet(any(), any());
    }

    @Test
    void aFailedRouteRetargetPutsThePriorTargetBack() {
        Route route = new Route();
        route.setDestinationCidrBlock("0.0.0.0/0");
        route.setGatewayId(IGW_ID);
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(RTB_ID);
        rt.setRoutes(List.of(route));
        when(ec2.describeRouteTables(REGION, List.of(RTB_ID), Map.of())).thenReturn(List.of(rt));
        doThrow(new AwsException("InvalidNatGatewayID.NotFound", "gone", 400)).doNothing()
                .when(ec2).replaceRoute(eq(REGION), eq(RTB_ID), eq("0.0.0.0/0"), isNull(), isNull(), any(), any(), any());
        StackResource r = prior("AWS::EC2::Route", "Default", RTB_ID + "|0.0.0.0/0", Map.of("CidrBlock", "0.0.0.0/0"));

        assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode().put("RouteTableId", RTB_ID)
                .put("DestinationCidrBlock", "0.0.0.0/0").put("NatGatewayId", NAT_ID), ctx(RTB_ID + "|0.0.0.0/0")));

        verify(ec2).replaceRoute(REGION, RTB_ID, "0.0.0.0/0", null, null, null, NAT_ID, null);
        verify(ec2).replaceRoute(REGION, RTB_ID, "0.0.0.0/0", null, null, IGW_ID, null, null);
        assertFalse(r.getAttributes().containsKey(Ec2NetworkCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aFailedNatGatewayTagChangePutsThePriorTagsBack() {
        NatGateway nat = new NatGateway();
        nat.setNatGatewayId(NAT_ID);
        nat.setSubnetId(SUBNET_ID);
        nat.setAllocationId(ALLOC_ID);
        nat.setTags(List.of(new Tag("Name", "old")));
        when(ec2.describeNatGateways(REGION, List.of(NAT_ID), Map.of())).thenReturn(List.of(nat));
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).doNothing()
                .when(ec2).createTags(eq(REGION), eq(List.of(NAT_ID)), any());
        StackResource r = prior("AWS::EC2::NatGateway", "Nat", NAT_ID, Map.of("NatGatewayId", NAT_ID));

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("SubnetId", SUBNET_ID).put("AllocationId", ALLOC_ID), "Name", "new"), ctx(NAT_ID)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> written = ArgumentCaptor.forClass(List.class);
        verify(ec2, org.mockito.Mockito.times(2)).createTags(eq(REGION), eq(List.of(NAT_ID)), written.capture());
        assertEquals(List.of("Name=old"), pairs(written.getAllValues().get(1)));
        verify(ec2, never()).deleteNatGateway(any(), any());
    }

    @Test
    void aFailedStepOnAReusedEntityLeavesItAlone() {
        when(ec2.describeSubnets(REGION, List.of(SUBNET_ID), Map.of())).thenReturn(List.of(subnet()));
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());
        StackResource r = prior("AWS::EC2::Subnet", "Subnet", SUBNET_ID, Map.of("SubnetId", SUBNET_ID));

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                withTags(mapper.createObjectNode().put("VpcId", VPC_ID).put("CidrBlock", "10.0.1.0/24"), "k", "v"), ctx(SUBNET_ID)));

        verify(ec2, never()).deleteSubnet(any(), any());
    }

    @Test
    void unknownTypeIsRejected() {
        StackResource r = resource("AWS::EC2::SecurityGroup", "Sg");

        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }
}
