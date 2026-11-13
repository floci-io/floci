package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRuleRequest;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRuleUpdate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2RuleDescriptionIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkDescriptionUpdatePreservesTheRuleUntilGroupDeletion(boolean egress) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "555566668904", Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.234.0.0/24")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String name = "rule-description-" + UUID.randomUUID();
        cleanup.register(() -> client.describeSecurityGroups(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build(),
                Filter.builder().name("group-name").values(name).build())).securityGroups()
                .forEach(group -> client.deleteSecurityGroup(request -> request.groupId(group.groupId()))));
        String group = client.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("owned description fixture")).groupId();
        IpPermission permission = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp("10.234.0.0/24").description("before").build()).build();
        List<SecurityGroupRule> created = egress
                ? client.authorizeSecurityGroupEgress(request -> request.groupId(group)
                        .ipPermissions(permission)).securityGroupRules()
                : client.authorizeSecurityGroupIngress(request -> request.groupId(group)
                        .ipPermissions(permission)).securityGroupRules();
        assertEquals(1, created.size());
        String rule = created.getFirst().securityGroupRuleId();
        SecurityGroupRule before = client.describeSecurityGroupRules(request -> request
                .securityGroupRuleIds(rule)).securityGroupRules().getFirst();

        assertTrue(client.modifySecurityGroupRules(request -> request.groupId(group).securityGroupRules(
                SecurityGroupRuleUpdate.builder().securityGroupRuleId(rule).securityGroupRule(
                        SecurityGroupRuleRequest.builder().description("after").build()).build())).returnValue());
        SecurityGroupRule after = client.describeSecurityGroupRules(request -> request
                .securityGroupRuleIds(rule)).securityGroupRules().getFirst();
        assertEquals(before.toBuilder().description("after").build(), after);

        client.deleteSecurityGroup(request -> request.groupId(group));
        assertTrue(client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build())).securityGroupRules().isEmpty());
    }
}
