package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AWS joins every filter with an AND, so a filter name given twice narrows the answer twice
 * instead of the second filter replacing the first.
 */
@DisplayName("EC2 Describe actions AND filters that repeat a name")
class Ec2RepeatedFilterNamesTest {

    private static Filter filter(String name, String... values) {
        return Filter.builder().name(name).values(values).build();
    }

    @Test
    @DisplayName("Two tag-key filters find only the VPC that carries both keys")
    void twoTagKeyFiltersFindTheVpcWithBothKeys() {
        String only = TestFixtures.uniqueName("RepeatedOnly");
        String shared = TestFixtures.uniqueName("RepeatedShared");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String both = ec2.createVpc(r -> r.cidrBlock("10.181.0.0/16")).vpc().vpcId();
            String one = ec2.createVpc(r -> r.cidrBlock("10.182.0.0/16")).vpc().vpcId();
            try {
                ec2.createTags(r -> r.resources(both, one).tags(t -> t.key(shared).value("v")));
                ec2.createTags(r -> r.resources(both).tags(t -> t.key(only).value("v")));

                assertThat(vpcIds(ec2, filter("tag-key", shared))).containsExactlyInAnyOrder(both, one);
                assertThat(vpcIds(ec2, filter("tag-key", only), filter("tag-key", shared))).containsExactly(both);
                assertThat(vpcIds(ec2, filter("tag-key", shared), filter("tag-key", only))).containsExactly(both);
            } finally {
                ec2.deleteVpc(r -> r.vpcId(both));
                ec2.deleteVpc(r -> r.vpcId(one));
            }
        }
    }

    @Test
    @DisplayName("Two vpc-id filters naming different VPCs find no subnet")
    void twoVpcIdFiltersFindNoSubnet() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String first = ec2.createVpc(r -> r.cidrBlock("10.183.0.0/16")).vpc().vpcId();
            String second = ec2.createVpc(r -> r.cidrBlock("10.184.0.0/16")).vpc().vpcId();
            String firstSubnet = null;
            String secondSubnet = null;
            try {
                firstSubnet = ec2.createSubnet(r -> r.vpcId(first).cidrBlock("10.183.1.0/24")).subnet().subnetId();
                secondSubnet = ec2.createSubnet(r -> r.vpcId(second).cidrBlock("10.184.1.0/24")).subnet().subnetId();

                assertThat(subnetIds(ec2, filter("vpc-id", second))).containsExactly(secondSubnet);
                assertThat(subnetIds(ec2, filter("vpc-id", first), filter("vpc-id", second))).isEmpty();
                assertThat(subnetIds(ec2, filter("vpc-id", first, second), filter("vpc-id", second)))
                        .containsExactly(secondSubnet);
            } finally {
                for (String subnet : new String[] {firstSubnet, secondSubnet}) {
                    if (subnet != null) {
                        ec2.deleteSubnet(r -> r.subnetId(subnet));
                    }
                }
                ec2.deleteVpc(r -> r.vpcId(first));
                ec2.deleteVpc(r -> r.vpcId(second));
            }
        }
    }

    @Test
    @DisplayName("Two group-id filters naming different groups find no security group rule")
    void twoGroupIdFiltersFindNoSecurityGroupRule() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpc = ec2.createVpc(r -> r.cidrBlock("10.185.0.0/16")).vpc().vpcId();
            String first = null;
            String second = null;
            try {
                first = ec2.createSecurityGroup(r -> r.vpcId(vpc).groupName(TestFixtures.uniqueName("repeated"))
                        .description("repeated filters")).groupId();
                second = ec2.createSecurityGroup(r -> r.vpcId(vpc).groupName(TestFixtures.uniqueName("repeated"))
                        .description("repeated filters")).groupId();
                String firstGroup = first;
                String secondGroup = second;

                assertThat(ruleGroups(ec2, filter("group-id", secondGroup))).isNotEmpty().containsOnly(secondGroup);
                assertThat(ruleGroups(ec2, filter("group-id", firstGroup), filter("group-id", secondGroup))).isEmpty();
            } finally {
                for (String group : new String[] {first, second}) {
                    if (group != null) {
                        ec2.deleteSecurityGroup(r -> r.groupId(group));
                    }
                }
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private static List<String> vpcIds(Ec2Client ec2, Filter... filters) {
        return ec2.describeVpcs(r -> r.filters(filters)).vpcs().stream().map(Vpc::vpcId).toList();
    }

    private static List<String> subnetIds(Ec2Client ec2, Filter... filters) {
        return ec2.describeSubnets(r -> r.filters(filters)).subnets().stream().map(Subnet::subnetId).toList();
    }

    private static List<String> ruleGroups(Ec2Client ec2, Filter... filters) {
        return ec2.describeSecurityGroupRules(r -> r.filters(filters)).securityGroupRules().stream()
                .map(SecurityGroupRule::groupId).toList();
    }
}
