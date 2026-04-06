package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EC2 Elastic Compute Cloud")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2Tests {

    private static Ec2Client ec2;
    private static String vpcId;
    private static String subnetId;
    private static String sgId;
    private static String keyName;
    private static String igwId;
    private static String rtId;
    private static String rtbAssocId;
    private static String allocationId;
    private static String instanceId;

    @BeforeAll
    static void setup() {
        ec2 = TestFixtures.ec2Client();
        keyName = "sdk-test-key";
    }

    @AfterAll
    static void cleanup() {
        if (ec2 != null) {
            try {
                if (instanceId != null) {
                    ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (allocationId != null) {
                    ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(allocationId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (rtbAssocId != null) {
                    ec2.disassociateRouteTable(DisassociateRouteTableRequest.builder().associationId(rtbAssocId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (igwId != null && vpcId != null) {
                    ec2.detachInternetGateway(DetachInternetGatewayRequest.builder().internetGatewayId(igwId).vpcId(vpcId).build());
                    ec2.deleteInternetGateway(DeleteInternetGatewayRequest.builder().internetGatewayId(igwId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (rtId != null) {
                    ec2.deleteRouteTable(DeleteRouteTableRequest.builder().routeTableId(rtId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (subnetId != null) {
                    ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (sgId != null) {
                    ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder().groupId(sgId).build());
                }
            } catch (Exception ignored) {}
            try {
                if (keyName != null) {
                    ec2.deleteKeyPair(DeleteKeyPairRequest.builder().keyName(keyName).build());
                }
            } catch (Exception ignored) {}
            try {
                if (vpcId != null) {
                    ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
                }
            } catch (Exception ignored) {}
            ec2.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("DescribeVpcs - default VPC exists")
    void describeVpcsDefaultExists() {
        DescribeVpcsResponse resp = ec2.describeVpcs();
        boolean hasDefault = resp.vpcs().stream().anyMatch(Vpc::isDefault);
        assertThat(hasDefault).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("DescribeSubnets - at least 3 default subnets")
    void describeSubnetsDefaultCount() {
        DescribeSubnetsResponse resp = ec2.describeSubnets();
        long defaultCount = resp.subnets().stream().filter(Subnet::defaultForAz).count();
        assertThat(defaultCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(3)
    @DisplayName("DescribeSecurityGroups - default SG exists")
    void describeSecurityGroupsDefault() {
        DescribeSecurityGroupsResponse resp = ec2.describeSecurityGroups();
        boolean hasDefault = resp.securityGroups().stream()
                .anyMatch(sg -> "default".equals(sg.groupName()));
        assertThat(hasDefault).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("DescribeAvailabilityZones - 3 zones")
    void describeAvailabilityZones() {
        DescribeAvailabilityZonesResponse resp = ec2.describeAvailabilityZones();
        assertThat(resp.availabilityZones()).hasSize(3);
    }

    @Test
    @Order(5)
    @DisplayName("DescribeRegions - non-empty list")
    void describeRegions() {
        DescribeRegionsResponse resp = ec2.describeRegions();
        assertThat(resp.regions()).isNotEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("DescribeImages - static AMI list")
    void describeImages() {
        DescribeImagesResponse resp = ec2.describeImages();
        assertThat(resp.images()).isNotEmpty();
        assertThat(resp.images()).allMatch(img -> img.imageId().startsWith("ami-"));
    }

    @Test
    @Order(7)
    @DisplayName("DescribeInstanceTypes - non-empty list")
    void describeInstanceTypes() {
        DescribeInstanceTypesResponse resp = ec2.describeInstanceTypes(
                DescribeInstanceTypesRequest.builder().build());
        assertThat(resp.instanceTypes()).isNotEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("CreateVpc - create VPC with CIDR")
    void createVpc() {
        CreateVpcResponse resp = ec2.createVpc(CreateVpcRequest.builder()
                .cidrBlock("10.0.0.0/16").build());
        vpcId = resp.vpc().vpcId();

        assertThat(vpcId).isNotNull().startsWith("vpc-");
        assertThat(resp.vpc().cidrBlock()).isEqualTo("10.0.0.0/16");
        assertThat(resp.vpc().state()).isEqualTo(VpcState.AVAILABLE);
    }

    @Test
    @Order(9)
    @DisplayName("DescribeVpcs - by ID")
    void describeVpcsById() {
        DescribeVpcsResponse resp = ec2.describeVpcs(DescribeVpcsRequest.builder()
                .vpcIds(vpcId).build());

        assertThat(resp.vpcs()).hasSize(1);
        assertThat(resp.vpcs().get(0).vpcId()).isEqualTo(vpcId);
    }

    @Test
    @Order(10)
    @DisplayName("DescribeVpcs - non-existent ID returns error")
    void describeVpcsNotFound() {
        assertThatThrownBy(() -> ec2.describeVpcs(DescribeVpcsRequest.builder()
                .vpcIds("vpc-doesnotexist").build()))
                .isInstanceOf(Ec2Exception.class)
                .satisfies(e -> {
                    Ec2Exception ec2Ex = (Ec2Exception) e;
                    assertThat(ec2Ex.awsErrorDetails().errorCode()).isEqualTo("InvalidVpcID.NotFound");
                });
    }

    @Test
    @Order(11)
    @DisplayName("CreateSubnet - create subnet in VPC")
    void createSubnet() {
        CreateSubnetResponse resp = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId)
                .cidrBlock("10.0.1.0/24")
                .availabilityZone("us-east-1a")
                .build());
        subnetId = resp.subnet().subnetId();

        assertThat(subnetId).isNotNull().startsWith("subnet-");
        assertThat(resp.subnet().vpcId()).isEqualTo(vpcId);
        assertThat(resp.subnet().cidrBlock()).isEqualTo("10.0.1.0/24");
    }

    @Test
    @Order(12)
    @DisplayName("DescribeSubnets - by ID")
    void describeSubnetsById() {
        DescribeSubnetsResponse resp = ec2.describeSubnets(DescribeSubnetsRequest.builder()
                .subnetIds(subnetId).build());

        assertThat(resp.subnets()).hasSize(1);
        assertThat(resp.subnets().get(0).subnetId()).isEqualTo(subnetId);
    }

    @Test
    @Order(13)
    @DisplayName("CreateSecurityGroup - create SG in VPC")
    void createSecurityGroup() {
        CreateSecurityGroupResponse resp = ec2.createSecurityGroup(
                CreateSecurityGroupRequest.builder()
                        .groupName("sdk-test-sg")
                        .description("SDK test security group")
                        .vpcId(vpcId)
                        .build());
        sgId = resp.groupId();

        assertThat(sgId).isNotNull().startsWith("sg-");
    }

    @Test
    @Order(14)
    @DisplayName("AuthorizeSecurityGroupIngress - add SSH rule")
    void authorizeSecurityGroupIngress() {
        ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(sgId)
                .ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(22)
                        .toPort(22)
                        .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                        .build())
                .build());
    }

    @Test
    @Order(15)
    @DisplayName("DescribeSecurityGroups - ingress rule present")
    void describeSecurityGroupsIngressRule() {
        DescribeSecurityGroupsResponse resp = ec2.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder().groupIds(sgId).build());

        boolean hasSshRule = resp.securityGroups().get(0).ipPermissions().stream()
                .anyMatch(p -> p.fromPort() != null && p.fromPort() == 22);
        assertThat(hasSshRule).isTrue();
    }

    @Test
    @Order(16)
    @DisplayName("CreateKeyPair - create SSH key pair")
    void createKeyPair() {
        CreateKeyPairResponse resp = ec2.createKeyPair(CreateKeyPairRequest.builder()
                .keyName(keyName).build());

        assertThat(resp.keyName()).isEqualTo(keyName);
        assertThat(resp.keyPairId()).isNotNull();
        assertThat(resp.keyMaterial()).isNotNull().isNotEmpty();
    }

    @Test
    @Order(17)
    @DisplayName("DescribeKeyPairs - key pair exists")
    void describeKeyPairs() {
        DescribeKeyPairsResponse resp = ec2.describeKeyPairs(DescribeKeyPairsRequest.builder()
                .keyNames(keyName).build());

        assertThat(resp.keyPairs()).hasSize(1);
        assertThat(resp.keyPairs().get(0).keyName()).isEqualTo(keyName);
    }

    @Test
    @Order(18)
    @DisplayName("CreateKeyPair - duplicate returns error")
    void createKeyPairDuplicate() {
        assertThatThrownBy(() -> ec2.createKeyPair(CreateKeyPairRequest.builder()
                .keyName(keyName).build()))
                .isInstanceOf(Ec2Exception.class)
                .satisfies(e -> {
                    Ec2Exception ec2Ex = (Ec2Exception) e;
                    assertThat(ec2Ex.awsErrorDetails().errorCode()).isEqualTo("InvalidKeyPair.Duplicate");
                });
    }

    @Test
    @Order(19)
    @DisplayName("CreateInternetGateway - create IGW")
    void createInternetGateway() {
        CreateInternetGatewayResponse resp = ec2.createInternetGateway(
                CreateInternetGatewayRequest.builder().build());
        igwId = resp.internetGateway().internetGatewayId();

        assertThat(igwId).isNotNull().startsWith("igw-");
    }

    @Test
    @Order(20)
    @DisplayName("AttachInternetGateway - attach to VPC")
    void attachInternetGateway() {
        ec2.attachInternetGateway(AttachInternetGatewayRequest.builder()
                .internetGatewayId(igwId)
                .vpcId(vpcId)
                .build());
    }

    @Test
    @Order(21)
    @DisplayName("DescribeInternetGateways - attachment reflected")
    void describeInternetGatewaysAttached() {
        DescribeInternetGatewaysResponse resp = ec2.describeInternetGateways(
                DescribeInternetGatewaysRequest.builder()
                        .internetGatewayIds(igwId).build());

        boolean attached = resp.internetGateways().get(0).attachments().stream()
                .anyMatch(a -> vpcId.equals(a.vpcId()));
        assertThat(attached).isTrue();
    }

    @Test
    @Order(22)
    @DisplayName("CreateRouteTable - create route table")
    void createRouteTable() {
        CreateRouteTableResponse resp = ec2.createRouteTable(CreateRouteTableRequest.builder()
                .vpcId(vpcId).build());
        rtId = resp.routeTable().routeTableId();

        assertThat(rtId).isNotNull().startsWith("rtb-");
        assertThat(resp.routeTable().vpcId()).isEqualTo(vpcId);
    }

    @Test
    @Order(23)
    @DisplayName("CreateRoute - add default route to IGW")
    void createRoute() {
        ec2.createRoute(CreateRouteRequest.builder()
                .routeTableId(rtId)
                .destinationCidrBlock("0.0.0.0/0")
                .gatewayId(igwId)
                .build());
    }

    @Test
    @Order(24)
    @DisplayName("AssociateRouteTable - associate with subnet")
    void associateRouteTable() {
        AssociateRouteTableResponse resp = ec2.associateRouteTable(
                AssociateRouteTableRequest.builder()
                        .routeTableId(rtId)
                        .subnetId(subnetId)
                        .build());
        rtbAssocId = resp.associationId();

        assertThat(rtbAssocId).isNotNull().startsWith("rtbassoc-");
    }

    @Test
    @Order(25)
    @DisplayName("AllocateAddress - allocate EIP")
    void allocateAddress() {
        AllocateAddressResponse resp = ec2.allocateAddress(AllocateAddressRequest.builder()
                .domain(DomainType.VPC).build());
        allocationId = resp.allocationId();

        assertThat(allocationId).isNotNull().startsWith("eipalloc-");
        assertThat(resp.publicIp()).isNotNull();
    }

    @Test
    @Order(26)
    @DisplayName("DescribeAddresses - EIP exists")
    void describeAddresses() {
        DescribeAddressesResponse resp = ec2.describeAddresses(
                DescribeAddressesRequest.builder()
                        .allocationIds(allocationId).build());

        assertThat(resp.addresses()).hasSize(1);
        assertThat(resp.addresses().get(0).allocationId()).isEqualTo(allocationId);
    }

    @Test
    @Order(27)
    @DisplayName("RunInstances - launch EC2 instance")
    void runInstances() {
        RunInstancesResponse resp = ec2.runInstances(RunInstancesRequest.builder()
                .imageId("ami-0abcdef1234567890")
                .instanceType(InstanceType.T2_MICRO)
                .minCount(1)
                .maxCount(1)
                .keyName(keyName)
                .subnetId(subnetId)
                .securityGroupIds(List.of(sgId))
                .build());
        instanceId = resp.instances().get(0).instanceId();
        Instance launched = resp.instances().get(0);

        assertThat(instanceId).isNotNull().startsWith("i-");
        assertThat(launched.state().name()).isEqualTo(InstanceStateName.RUNNING);
        assertThat(launched.instanceType()).isEqualTo(InstanceType.T2_MICRO);
        assertThat(launched.keyName()).isEqualTo(keyName);
    }

    @Test
    @Order(28)
    @DisplayName("DescribeInstances - by ID")
    void describeInstancesById() {
        DescribeInstancesResponse resp = ec2.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build());
        Instance found = resp.reservations().get(0).instances().get(0);

        assertThat(found.instanceId()).isEqualTo(instanceId);
        assertThat(found.state().name()).isEqualTo(InstanceStateName.RUNNING);
    }

    @Test
    @Order(29)
    @DisplayName("DescribeInstances - filter by state")
    void describeInstancesFilterByState() {
        DescribeInstancesResponse resp = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                        .filters(Filter.builder()
                                .name("instance-state-name")
                                .values("running")
                                .build())
                        .build());

        boolean found = resp.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .anyMatch(i -> instanceId.equals(i.instanceId()));
        assertThat(found).isTrue();
    }

    @Test
    @Order(30)
    @DisplayName("DescribeInstanceStatus - instance status available")
    void describeInstanceStatus() {
        DescribeInstanceStatusResponse resp = ec2.describeInstanceStatus(
                DescribeInstanceStatusRequest.builder().instanceIds(instanceId).build());

        assertThat(resp.instanceStatuses()).isNotEmpty();
        assertThat(resp.instanceStatuses().get(0).instanceId()).isEqualTo(instanceId);
    }

    @Test
    @Order(31)
    @DisplayName("AssociateAddress - associate EIP to instance")
    void associateAddress() {
        AssociateAddressResponse resp = ec2.associateAddress(
                AssociateAddressRequest.builder()
                        .allocationId(allocationId)
                        .instanceId(instanceId)
                        .build());
        String assocId = resp.associationId();

        assertThat(assocId).isNotNull().startsWith("eipassoc-");
    }

    @Test
    @Order(32)
    @DisplayName("DisassociateAddress - disassociate EIP")
    void disassociateAddress() {
        // Get the association ID first
        DescribeAddressesResponse addrResp = ec2.describeAddresses(
                DescribeAddressesRequest.builder().allocationIds(allocationId).build());
        String assocId = addrResp.addresses().get(0).associationId();

        if (assocId != null) {
            ec2.disassociateAddress(DisassociateAddressRequest.builder()
                    .associationId(assocId).build());
        }
    }

    @Test
    @Order(33)
    @DisplayName("StopInstances - stop instance")
    void stopInstances() {
        StopInstancesResponse resp = ec2.stopInstances(StopInstancesRequest.builder()
                .instanceIds(instanceId).build());

        assertThat(resp.stoppingInstances().get(0).currentState().name())
                .isEqualTo(InstanceStateName.STOPPED);
    }

    @Test
    @Order(34)
    @DisplayName("StartInstances - start instance")
    void startInstances() {
        StartInstancesResponse resp = ec2.startInstances(StartInstancesRequest.builder()
                .instanceIds(instanceId).build());

        assertThat(resp.startingInstances().get(0).currentState().name())
                .isEqualTo(InstanceStateName.RUNNING);
    }

    @Test
    @Order(35)
    @DisplayName("RebootInstances - reboot instance")
    void rebootInstances() {
        ec2.rebootInstances(RebootInstancesRequest.builder()
                .instanceIds(instanceId).build());
    }

    @Test
    @Order(36)
    @DisplayName("DescribeInstances - non-existent ID returns error")
    void describeInstancesNotFound() {
        assertThatThrownBy(() -> ec2.describeInstances(DescribeInstancesRequest.builder()
                .instanceIds("i-0000000000000dead").build()))
                .isInstanceOf(Ec2Exception.class)
                .satisfies(e -> {
                    Ec2Exception ec2Ex = (Ec2Exception) e;
                    assertThat(ec2Ex.awsErrorDetails().errorCode()).isEqualTo("InvalidInstanceID.NotFound");
                });
    }

    @Test
    @Order(37)
    @DisplayName("CreateTags - add tags to instance")
    void createTags() {
        ec2.createTags(CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(software.amazon.awssdk.services.ec2.model.Tag.builder().key("Name").value("sdk-test-instance").build())
                .build());
    }

    @Test
    @Order(38)
    @DisplayName("DescribeInstances - tags reflected on instance")
    void describeInstancesTagsReflected() {
        DescribeInstancesResponse resp = ec2.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build());

        boolean hasTag = resp.reservations().get(0).instances().get(0).tags().stream()
                .anyMatch(t -> "Name".equals(t.key()) && "sdk-test-instance".equals(t.value()));
        assertThat(hasTag).isTrue();
    }

    @Test
    @Order(39)
    @DisplayName("TerminateInstances - terminate instance")
    void terminateInstances() {
        TerminateInstancesResponse resp = ec2.terminateInstances(
                TerminateInstancesRequest.builder().instanceIds(instanceId).build());

        assertThat(resp.terminatingInstances().get(0).currentState().name())
                .isEqualTo(InstanceStateName.TERMINATED);
    }

    @Test
    @Order(40)
    @DisplayName("ReleaseAddress - release EIP")
    void releaseAddress() {
        ec2.releaseAddress(ReleaseAddressRequest.builder()
                .allocationId(allocationId).build());
        allocationId = null;
    }

    @Test
    @Order(41)
    @DisplayName("DisassociateRouteTable - disassociate route table")
    void disassociateRouteTable() {
        ec2.disassociateRouteTable(DisassociateRouteTableRequest.builder()
                .associationId(rtbAssocId).build());
        rtbAssocId = null;
    }

    @Test
    @Order(42)
    @DisplayName("DetachAndDeleteInternetGateway - cleanup IGW")
    void detachAndDeleteInternetGateway() {
        ec2.detachInternetGateway(DetachInternetGatewayRequest.builder()
                .internetGatewayId(igwId).vpcId(vpcId).build());
        ec2.deleteInternetGateway(DeleteInternetGatewayRequest.builder()
                .internetGatewayId(igwId).build());
        igwId = null;
    }

    @Test
    @Order(43)
    @DisplayName("DeleteRouteTable - delete route table")
    void deleteRouteTable() {
        ec2.deleteRouteTable(DeleteRouteTableRequest.builder()
                .routeTableId(rtId).build());
        rtId = null;
    }

    @Test
    @Order(44)
    @DisplayName("DeleteSubnet - delete subnet")
    void deleteSubnet() {
        ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
        subnetId = null;
    }

    @Test
    @Order(45)
    @DisplayName("DeleteSecurityGroup - delete security group")
    void deleteSecurityGroup() {
        ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                .groupId(sgId).build());
        sgId = null;
    }

    @Test
    @Order(46)
    @DisplayName("DeleteKeyPair - delete key pair")
    void deleteKeyPair() {
        ec2.deleteKeyPair(DeleteKeyPairRequest.builder().keyName(keyName).build());
    }

    @Test
    @Order(47)
    @DisplayName("DeleteVpc - delete VPC")
    void deleteVpc() {
        ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
        vpcId = null;
    }
}
