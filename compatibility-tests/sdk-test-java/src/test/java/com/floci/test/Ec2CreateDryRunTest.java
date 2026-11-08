package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A create with DryRun answers DryRunOperation (HTTP 412) and creates nothing, so the same create
 * without DryRun then succeeds. A subnet or group dry run naming a missing VPC keeps the real create's
 * error.
 */
class Ec2CreateDryRunTest {

    @Test
    @DisplayName("CreateVpc with DryRun creates no VPC")
    void createVpcWithDryRun() {
        String marker = TestFixtures.uniqueName("compat-dry-run");
        TagSpecification nameTag = TagSpecification.builder().resourceType(ResourceType.VPC)
                .tags(Tag.builder().key("Name").value(marker).build()).build();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            try {
                expectDryRunOperation(() -> ec2.createVpc(r -> r.cidrBlock("10.212.0.0/16")
                        .tagSpecifications(nameTag).dryRun(true)));
                assertThat(ec2.describeVpcs(r -> r.filters(byName(marker))).vpcs()).isEmpty();

                ec2.createVpc(r -> r.cidrBlock("10.212.0.0/16").tagSpecifications(nameTag));
                assertThat(ec2.describeVpcs(r -> r.filters(byName(marker))).vpcs()).hasSize(1);
            } finally {
                ec2.describeVpcs(r -> r.filters(byName(marker))).vpcs()
                        .forEach(vpc -> ec2.deleteVpc(r -> r.vpcId(vpc.vpcId())));
            }
        }
    }

    @Test
    @DisplayName("CreateSubnet with DryRun creates no subnet and leaves its CIDR block free")
    void createSubnetWithDryRun() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.213.0.0/16")).vpc().vpcId();
            try {
                expectDryRunOperation(() -> ec2.createSubnet(r -> r.vpcId(vpcId)
                        .cidrBlock("10.213.8.0/24").dryRun(true)));
                assertThat(ec2.describeSubnets(r -> r.filters(byVpc(vpcId))).subnets()).isEmpty();

                assertThat(ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.213.8.0/24")).subnet().cidrBlock())
                        .isEqualTo("10.213.8.0/24");
            } finally {
                ec2.describeSubnets(r -> r.filters(byVpc(vpcId))).subnets()
                        .forEach(subnet -> ec2.deleteSubnet(r -> r.subnetId(subnet.subnetId())));
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("CreateSecurityGroup with DryRun creates no group and leaves its name free")
    void createSecurityGroupWithDryRun() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.214.0.0/16")).vpc().vpcId();
            try {
                expectDryRunOperation(() -> ec2.createSecurityGroup(r -> r.vpcId(vpcId)
                        .groupName("compat-dry-run-sg").description("compatibility dry run").dryRun(true)));
                assertThat(ec2.describeSecurityGroups(r -> r.filters(byVpc(vpcId))).securityGroups())
                        .extracting(SecurityGroup::groupName).containsExactly("default");

                assertThat(ec2.createSecurityGroup(r -> r.vpcId(vpcId)
                        .groupName("compat-dry-run-sg").description("compatibility dry run")).groupId())
                        .isNotBlank();
            } finally {
                ec2.describeSecurityGroups(r -> r.filters(byVpc(vpcId))).securityGroups().stream()
                        .filter(group -> !"default".equals(group.groupName()))
                        .forEach(group -> ec2.deleteSecurityGroup(r -> r.groupId(group.groupId())));
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }

    @Test
    @DisplayName("CreateSubnet with DryRun in a missing VPC keeps InvalidVpcID.NotFound")
    void createSubnetWithDryRunInAMissingVpc() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.createSubnet(r -> r
                    .vpcId("vpc-0123456789abcdef0").cidrBlock("10.215.8.0/24").dryRun(true)));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidVpcID.NotFound");
        }
    }

    @Test
    @DisplayName("CreateSecurityGroup with DryRun in a missing VPC keeps InvalidVpcID.NotFound")
    void createSecurityGroupWithDryRunInAMissingVpc() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.createSecurityGroup(r -> r
                    .vpcId("vpc-0123456789abcdef0").groupName("compat-dry-run-missing-vpc")
                    .description("compatibility dry run").dryRun(true)));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidVpcID.NotFound");
        }
    }

    private static Filter byName(String name) {
        return Filter.builder().name("tag:Name").values(name).build();
    }

    private static Filter byVpc(String vpcId) {
        return Filter.builder().name("vpc-id").values(vpcId).build();
    }

    private static void expectDryRunOperation(Executable call) {
        Ec2Exception error = assertThrows(Ec2Exception.class, call);
        assertThat(error.statusCode()).isEqualTo(412);
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("DryRunOperation");
    }
}
