package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.Filter;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2VpcDefaultResourceCleanupTest {

    @Test
    void deleteVpcRemovesVpcOwnedDefaultResourcesAndSecurityGroupRules() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(CreateVpcRequest.builder()
                            .cidrBlock("10.77.0.0/16")
                            .build())
                    .vpc().vpcId();
            Filter vpcFilter = Filter.builder().name("vpc-id").values(vpcId).build();

            var defaultGroup = ec2.describeSecurityGroups(request -> request.filters(vpcFilter))
                    .securityGroups().stream()
                    .filter(group -> "default".equals(group.groupName()))
                    .findFirst().orElseThrow();
            var mainRouteTable = ec2.describeRouteTables(request -> request.filters(vpcFilter))
                    .routeTables().stream()
                    .filter(table -> table.associations().stream().anyMatch(association -> association.main()))
                    .findFirst().orElseThrow();
            var defaultNetworkAcl = ec2.describeNetworkAcls(request -> request.filters(vpcFilter))
                    .networkAcls().stream()
                    .filter(acl -> acl.isDefault())
                    .findFirst().orElseThrow();
            Filter groupFilter = Filter.builder().name("group-id").values(defaultGroup.groupId()).build();
            assertThat(ec2.describeSecurityGroupRules(request -> request.filters(groupFilter))
                    .securityGroupRules()).isNotEmpty();

            ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());

            assertThat(ec2.describeSecurityGroups(request -> request.groupIds(defaultGroup.groupId()))
                    .securityGroups()).isEmpty();
            assertThat(ec2.describeRouteTables(request -> request.routeTableIds(mainRouteTable.routeTableId()))
                    .routeTables()).isEmpty();
            assertThat(ec2.describeNetworkAcls(request -> request.networkAclIds(defaultNetworkAcl.networkAclId()))
                    .networkAcls()).isEmpty();
            assertThat(ec2.describeSecurityGroupRules(request -> request.filters(groupFilter))
                    .securityGroupRules()).isEmpty();
        }
    }
}
