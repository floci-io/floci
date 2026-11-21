package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2SecurityGroupTagDeletionIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionRemovesGroupAndRuleTagsButPreservesOtherResources(boolean checkRuleFirst) {
        Ec2Client client = client();
        String vpc = vpc(client);
        String group = group(client, vpc);
        String retainedGroup = group(client, vpc);
        String rule = rule(client, group);
        String retainedRule = rule(client, retainedGroup);
        tag(client, List.of(group, rule, retainedGroup, retainedRule, vpc));
        assertEquals(2, tagCount(client, List.of(group, rule)));

        client.deleteSecurityGroup(request -> request.groupId(group));

        String first = checkRuleFirst ? rule : group;
        String second = checkRuleFirst ? group : rule;
        assertEquals(0, tagCount(client, List.of(first)), "Deleted resource tags must be removed: " + first);
        assertEquals(0, tagCount(client, List.of(second)), "Deleted resource tags must be removed: " + second);
        assertEquals(3, tagCount(client, List.of(retainedGroup, retainedRule, vpc)));
        assertTrue(client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build())).securityGroupRules().isEmpty());
    }

    @Test
    void dryRunPreservesGroupAndRuleTags() {
        Ec2Client client = client();
        String group = group(client, vpc(client));
        String rule = rule(client, group);
        tag(client, List.of(group, rule));

        Ec2Exception error = assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(group).dryRun(true)));

        assertEquals("DryRunOperation", error.awsErrorDetails().errorCode());
        assertEquals(2, tagCount(client, List.of(group, rule)));
        assertEquals(1, client.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().size());
    }

    @Test
    void dependencyRejectionPreservesGroupAndRuleTags() {
        Ec2Client client = client();
        String vpc = vpc(client);
        String group = group(client, vpc);
        String rule = rule(client, group);
        tag(client, List.of(group, rule));
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.231.0.0/24"))
                .subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String networkInterface = client.createNetworkInterface(request -> request.subnetId(subnet).groups(group))
                .networkInterface().networkInterfaceId();
        cleanup.register(() -> client.deleteNetworkInterface(request -> request.networkInterfaceId(networkInterface)));

        Ec2Exception error = assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(group)));

        assertEquals("DependencyViolation", error.awsErrorDetails().errorCode());
        assertEquals(2, tagCount(client, List.of(group, rule)));
        assertEquals(1, client.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().size());
    }

    @Test
    void endpointGroupReplacementRetainsReferencesUntilRemovalOrDeletion() {
        Ec2Client client = client();
        String vpc = vpc(client);
        String original = group(client, vpc);
        String replacement = group(client, vpc);
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.231.1.0/24"))
                .subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String endpoint = client.createVpcEndpoint(request -> request.vpcId(vpc)
                .vpcEndpointType("Interface").serviceName("com.amazonaws.us-west-2.ssm")
                .subnetIds(subnet).securityGroupIds(original)).vpcEndpoint().vpcEndpointId();
        cleanup.register(() -> client.describeVpcEndpoints(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build())).vpcEndpoints().stream()
                .filter(value -> endpoint.equals(value.vpcEndpointId()))
                .forEach(value -> client.deleteVpcEndpoints(request -> request.vpcEndpointIds(endpoint))));
        tag(client, List.of(original, replacement, endpoint));

        assertEquals("DependencyViolation", assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(original))).awsErrorDetails().errorCode());
        assertEquals(3, tagCount(client, List.of(original, replacement, endpoint)));
        assertEquals("InvalidGroup.NotFound", assertThrows(Ec2Exception.class,
                () -> client.modifyVpcEndpoint(request -> request.vpcEndpointId(endpoint)
                        .addSecurityGroupIds("sg-00000000000000000").removeSecurityGroupIds(original)
                        .privateDnsEnabled(false))).awsErrorDetails().errorCode());
        assertEquals(List.of(original), client.describeVpcEndpoints(request -> request.vpcEndpointIds(endpoint))
                .vpcEndpoints().getFirst().groups().stream().map(value -> value.groupId()).toList());

        client.modifyVpcEndpoint(request -> request.vpcEndpointId(endpoint)
                .addSecurityGroupIds(replacement).removeSecurityGroupIds(original));
        client.deleteSecurityGroup(request -> request.groupId(original));
        assertEquals("DependencyViolation", assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(replacement))).awsErrorDetails().errorCode());
        client.deleteVpcEndpoints(request -> request.vpcEndpointIds(endpoint));
        client.deleteSecurityGroup(request -> request.groupId(replacement));
        assertEquals(0, tagCount(client, List.of(original, replacement, endpoint)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endpointReferencesCannotResolveGroupsInAnotherAccountOrRegion(boolean foreignAccount) {
        Ec2Client owner = client();
        String group = group(owner, vpc(owner));
        Ec2Client other = client(foreignAccount ? Region.US_WEST_2 : Region.US_EAST_1,
                foreignAccount ? "555566667790" : "555566667789");
        String otherVpc = vpc(other);
        assertEquals("InvalidGroup.NotFound", assertThrows(Ec2Exception.class,
                () -> other.createVpcEndpoint(request -> request.vpcId(otherVpc).vpcEndpointType("Interface")
                        .serviceName("test-service").securityGroupIds(group))).awsErrorDetails().errorCode());
        assertTrue(other.describeVpcEndpoints(request -> request.filters(
                Filter.builder().name("vpc-id").values(otherVpc).build())).vpcEndpoints().isEmpty());
        assertEquals(1, owner.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().size());
    }

    private Ec2Client client() {
        return client(Region.US_WEST_2, "555566667789");
    }

    private Ec2Client client(Region region, String account) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, region);
        cleanup.register(client::close);
        return client;
    }

    private String vpc(Ec2Client client) {
        String vpc = client.createVpc(request -> request.cidrBlock("10.231.0.0/16")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        return vpc;
    }

    private String group(Ec2Client client, String vpc) {
        String name = "tag-deletion-" + UUID.randomUUID();
        cleanup.register(() -> client.describeSecurityGroups(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build(),
                Filter.builder().name("group-name").values(name).build())).securityGroups()
                .forEach(group -> client.deleteSecurityGroup(request -> request.groupId(group.groupId()))));
        return client.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("owned tag cleanup fixture")).groupId();
    }

    private static String rule(Ec2Client client, String group) {
        return client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build()))
                .securityGroupRules().getFirst().securityGroupRuleId();
    }

    private static void tag(Ec2Client client, List<String> resources) {
        client.createTags(request -> request.resources(resources)
                .tags(Tag.builder().key("Owner").value("tag-deletion-fixture").build()));
    }

    private static int tagCount(Ec2Client client, List<String> resources) {
        return client.describeTags(request -> request.filters(
                Filter.builder().name("resource-id").values(resources).build())).tags().size();
    }
}
