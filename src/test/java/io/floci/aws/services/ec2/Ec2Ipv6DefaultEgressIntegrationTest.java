package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2Ipv6DefaultEgressIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultIpv6EgressSurvivesBothDescribeSurfaces(boolean ipv6AtCreation) {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            String vpcId = client.createVpc(r -> r.cidrBlock("10.234.0.0/16")
                    .amazonProvidedIpv6CidrBlock(ipv6AtCreation)).vpc().vpcId();
            List<String> groups = new ArrayList<>();
            try {
                String groupId = client.createSecurityGroup(r -> r.vpcId(vpcId)
                        .groupName("ipv6-egress-" + UUID.randomUUID()).description("default IPv6 egress")).groupId();
                groups.add(groupId);
                String changedId = null;
                if (!ipv6AtCreation) {
                    changedId = client.createSecurityGroup(r -> r.vpcId(vpcId)
                            .groupName("custom-egress-" + UUID.randomUUID()).description("custom egress")).groupId();
                    groups.add(changedId);
                    client.authorizeSecurityGroupEgress(r -> r.groupId(groups.get(1)).ipPermissions(
                            IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                                    .ipRanges(IpRange.builder().cidrIp("10.0.0.0/8").build()).build()));
                    client.associateVpcCidrBlock(r -> r.vpcId(vpcId).amazonProvidedIpv6CidrBlock(true));
                }
                SecurityGroup group = client.describeSecurityGroups(r -> r.groupIds(groupId))
                        .securityGroups().getFirst();
                assertEquals(List.of("::/0"), group.ipPermissionsEgress().stream()
                        .flatMap(permission -> permission.ipv6Ranges().stream()).map(range -> range.cidrIpv6()).toList());
                assertTrue(client.describeSecurityGroupRules(r -> r.filters(
                                Filter.builder().name("group-id").values(groupId).build()))
                        .securityGroupRules().stream().anyMatch(rule -> rule.isEgress() && "::/0".equals(rule.cidrIpv6())));
                SecurityGroup defaultGroup = client.describeSecurityGroups(r -> r.filters(
                                Filter.builder().name("vpc-id").values(vpcId).build(),
                                Filter.builder().name("group-name").values("default").build()))
                        .securityGroups().getFirst();
                assertTrue(defaultGroup.ipPermissionsEgress().stream()
                        .flatMap(permission -> permission.ipv6Ranges().stream()).anyMatch(range -> "::/0".equals(range.cidrIpv6())));
                if (changedId != null) {
                    SecurityGroup changed = client.describeSecurityGroups(r -> r.groupIds(groups.get(1)))
                            .securityGroups().getFirst();
                    assertTrue(changed.ipPermissionsEgress().stream()
                            .flatMap(permission -> permission.ipv6Ranges().stream()).findAny().isEmpty());
                }
            } finally {
                for (String group : groups) {
                    client.deleteSecurityGroup(r -> r.groupId(group));
                }
                client.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }
}
