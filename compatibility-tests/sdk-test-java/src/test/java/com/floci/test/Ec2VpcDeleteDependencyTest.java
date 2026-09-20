package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttachInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.CreateNetworkAclRequest;
import software.amazon.awssdk.services.ec2.model.CreateRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.DeleteNetworkAclRequest;
import software.amazon.awssdk.services.ec2.model.DeleteRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DetachInternetGatewayRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2VpcDeleteDependencyTest {

    @Test
    void deleteVpcFailsWithDependencyViolationUntilCallerCreatedResourcesAreRemoved() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.82.0.0/16").build())
                    .vpc().vpcId();
            String subnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                            .vpcId(vpcId).cidrBlock("10.82.1.0/24").build())
                    .subnet().subnetId();
            String groupId = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                            .vpcId(vpcId).groupName("dependency-violation-sg").description("dependency test").build())
                    .groupId();
            String routeTableId = ec2.createRouteTable(CreateRouteTableRequest.builder().vpcId(vpcId).build())
                    .routeTable().routeTableId();
            String aclId = ec2.createNetworkAcl(CreateNetworkAclRequest.builder().vpcId(vpcId).build())
                    .networkAcl().networkAclId();
            String igwId = ec2.createInternetGateway().internetGateway().internetGatewayId();
            ec2.attachInternetGateway(AttachInternetGatewayRequest.builder()
                    .internetGatewayId(igwId).vpcId(vpcId).build());

            assertDependencyViolation(ec2, vpcId);

            ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder().groupId(groupId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteRouteTable(DeleteRouteTableRequest.builder().routeTableId(routeTableId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.deleteNetworkAcl(DeleteNetworkAclRequest.builder().networkAclId(aclId).build());
            assertDependencyViolation(ec2, vpcId);
            ec2.detachInternetGateway(DetachInternetGatewayRequest.builder()
                    .internetGatewayId(igwId).vpcId(vpcId).build());
            ec2.deleteInternetGateway(DeleteInternetGatewayRequest.builder().internetGatewayId(igwId).build());

            ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
            Filter vpcFilter = Filter.builder().name("vpc-id").values(vpcId).build();
            assertThat(ec2.describeVpcs(request -> request.filters(vpcFilter)).vpcs()).isEmpty();
        }
    }

    private static void assertDependencyViolation(Ec2Client ec2, String vpcId) {
        assertThatThrownBy(() -> ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build()))
                .isInstanceOfSatisfying(Ec2Exception.class,
                        e -> assertThat(e.awsErrorDetails().errorCode()).isEqualTo("DependencyViolation"));
    }
}
