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
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRuleRequest;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRuleUpdate;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
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
        String rule = createRule(client, group, egress).securityGroupRuleId();
        SecurityGroupRule before = client.describeSecurityGroupRules(request -> request
                .securityGroupRuleIds(rule)).securityGroupRules().getFirst();
        IpPermission beforePermission = describedPermission(client, group, egress);

        assertTrue(client.modifySecurityGroupRules(request -> request.groupId(group).securityGroupRules(
                SecurityGroupRuleUpdate.builder().securityGroupRuleId(rule).securityGroupRule(
                        SecurityGroupRuleRequest.builder().description("after").build()).build())).returnValue());
        SecurityGroupRule after = client.describeSecurityGroupRules(request -> request
                .securityGroupRuleIds(rule)).securityGroupRules().getFirst();
        assertEquals(before.toBuilder().description("after").build(), after);
        assertEquals(beforePermission.toBuilder().ipRanges(beforePermission.ipRanges().getFirst().toBuilder()
                .description("after").build()).build(), describedPermission(client, group, egress));

        client.deleteSecurityGroup(request -> request.groupId(group));
        assertTrue(client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build())).securityGroupRules().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aForeignRuleRejectsTheWholeDescriptionUpdate(boolean egress) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "555566668904", Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.235.0.0/24")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String group = createGroup(client, vpc);
        String otherGroup = createGroup(client, vpc);
        SecurityGroupRule owned = createRule(client, group, egress);
        SecurityGroupRule foreign = createRule(client, otherGroup, egress);
        List<SecurityGroup> before = client.describeSecurityGroups(request -> request.groupIds(group, otherGroup))
                .securityGroups();

        assertAwsError("InvalidSecurityGroupRuleId.NotFound", 400, () -> client.modifySecurityGroupRules(request ->
                request.groupId(group).securityGroupRules(descriptionUpdate(owned), descriptionUpdate(foreign))));

        assertEquals(owned, client.describeSecurityGroupRules(request ->
                request.securityGroupRuleIds(owned.securityGroupRuleId())).securityGroupRules().getFirst());
        assertEquals(foreign, client.describeSecurityGroupRules(request ->
                request.securityGroupRuleIds(foreign.securityGroupRuleId())).securityGroupRules().getFirst());
        assertEquals(before, client.describeSecurityGroups(request -> request.groupIds(group, otherGroup))
                .securityGroups());
    }

    private String createGroup(Ec2Client client, String vpc) {
        String group = client.createSecurityGroup(request -> request.vpcId(vpc)
                .groupName("rule-owner-" + UUID.randomUUID()).description("owned rule fixture")).groupId();
        cleanup.register(() -> client.deleteSecurityGroup(request -> request.groupId(group)));
        return group;
    }

    private IpPermission describedPermission(Ec2Client client, String group, boolean egress) {
        SecurityGroup described = client.describeSecurityGroups(request -> request.groupIds(group))
                .securityGroups().getFirst();
        List<IpPermission> permissions = egress ? described.ipPermissionsEgress() : described.ipPermissions();
        return permissions.stream().filter(permission -> "tcp".equals(permission.ipProtocol()))
                .findFirst().orElseThrow();
    }

    private SecurityGroupRule createRule(Ec2Client client, String group, boolean egress) {
        IpPermission permission = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(IpRange.builder().cidrIp("10.235.0.0/24").description("before").build()).build();
        return (egress
                ? client.authorizeSecurityGroupEgress(request -> request.groupId(group)
                        .ipPermissions(permission)).securityGroupRules()
                : client.authorizeSecurityGroupIngress(request -> request.groupId(group)
                        .ipPermissions(permission)).securityGroupRules()).getFirst();
    }

    private SecurityGroupRuleUpdate descriptionUpdate(SecurityGroupRule rule) {
        return SecurityGroupRuleUpdate.builder().securityGroupRuleId(rule.securityGroupRuleId())
                .securityGroupRule(SecurityGroupRuleRequest.builder().description("after").build()).build();
    }
}
