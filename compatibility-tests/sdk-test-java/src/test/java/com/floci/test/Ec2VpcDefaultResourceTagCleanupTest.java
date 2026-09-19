package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2VpcDefaultResourceTagCleanupTest {

    @Test
    void deleteVpcRemovesTagsOnItsDefaultResources() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.83.0.0/16").build())
                    .vpc().vpcId();
            Filter vpcFilter = Filter.builder().name("vpc-id").values(vpcId).build();

            String groupId = ec2.describeSecurityGroups(request -> request.filters(vpcFilter))
                    .securityGroups().get(0).groupId();
            String routeTableId = ec2.describeRouteTables(request -> request.filters(vpcFilter))
                    .routeTables().get(0).routeTableId();
            String aclId = ec2.describeNetworkAcls(request -> request.filters(vpcFilter))
                    .networkAcls().get(0).networkAclId();
            Filter groupFilter = Filter.builder().name("group-id").values(groupId).build();
            String ruleId = ec2.describeSecurityGroupRules(request -> request.filters(groupFilter))
                    .securityGroupRules().get(0).securityGroupRuleId();
            List<String> defaultIds = List.of(groupId, routeTableId, aclId, ruleId);
            Filter resourceFilter = Filter.builder().name("resource-id").values(defaultIds).build();
            ec2.createTags(CreateTagsRequest.builder()
                    .resources(defaultIds)
                    .tags(Tag.builder().key("Name").value("doomed").build())
                    .build());
            assertThat(ec2.describeTags(request -> request.filters(resourceFilter)).tags()).hasSize(4);

            ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());

            assertThat(ec2.describeTags(request -> request.filters(resourceFilter)).tags()).isEmpty();
        }
    }
}
