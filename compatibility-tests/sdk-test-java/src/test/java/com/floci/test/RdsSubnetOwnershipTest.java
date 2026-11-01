package com.floci.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbProxyNotFoundException;
import software.amazon.awssdk.services.rds.model.InvalidSubnetException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Isolated
class RdsSubnetOwnershipTest {
    @Test
    void mixedVpcSubnetsRemainRejectedWithoutPublishingAProxy() {
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             RdsClient rds = TestFixtures.rdsClient();
             Ec2TestNetwork first = Ec2TestNetwork.create(ec2);
             Ec2TestNetwork second = Ec2TestNetwork.create(ec2,
                     List.of(TestFixtures.region().id() + "b"))) {
            String name = TestFixtures.uniqueName("rds-mixed-subnets");
            try {
                assertThatThrownBy(() -> rds.createDBProxy(request -> request.dbProxyName(name)
                        .engineFamily("POSTGRESQL")
                        .roleArn("arn:aws:iam::000000000000:role/rds-proxy")
                        .defaultAuthScheme("IAM_AUTH")
                        .vpcSubnetIds(first.subnetId(), second.subnetId())))
                        .isInstanceOf(InvalidSubnetException.class)
                        .hasMessageContaining("VpcSubnetIds must belong to one VPC.");
                assertThatThrownBy(() -> rds.describeDBProxies(request -> request.dbProxyName(name)))
                        .isInstanceOf(DbProxyNotFoundException.class);
            } finally {
                try {
                    rds.deleteDBProxy(request -> request.dbProxyName(name));
                } catch (DbProxyNotFoundException expected) {
                    // Rejected creation leaves no proxy; still clean up if the assertion fails.
                }
            }
        }
    }

    @Test
    void setupOwnsTwoSubnetsAndCleanupPreservesUnrelatedNetworks() {
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             Ec2TestNetwork unrelated = Ec2TestNetwork.create(ec2)) {
            Set<String> before = ec2.describeSubnets().subnets().stream()
                    .map(Subnet::subnetId).collect(Collectors.toSet());
            try {
                RdsControlPlaneTest.setup();
                List<Subnet> allocated = ec2.describeSubnets().subnets().stream()
                        .filter(subnet -> !before.contains(subnet.subnetId())).toList();
                assertThat(allocated).hasSize(2);
                assertThat(allocated).extracting(Subnet::vpcId).containsOnly(allocated.get(0).vpcId());
                assertThat(allocated).extracting(Subnet::availabilityZone).doesNotHaveDuplicates();
                assertThat(allocated).extracting(Subnet::vpcId).doesNotContain(unrelated.vpcId());
                new RdsControlPlaneTest().sdkUnmarshalsDbSubnetGroupSubnets();
            } finally {
                RdsControlPlaneTest.cleanup();
            }
            assertThat(ec2.describeSubnets().subnets()).extracting(Subnet::subnetId)
                    .containsExactlyInAnyOrderElementsOf(before);
        }
    }

    @Test
    void regionalProxyFixtureLeavesNoSubnetsOrVpcsBehind() {
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             Ec2TestNetwork unrelated = Ec2TestNetwork.create(ec2)) {
            Set<String> before = ec2.describeSubnets().subnets().stream()
                    .map(Subnet::subnetId).collect(Collectors.toSet());
            new RdsControlPlaneTest().sdkScopesTheSameDbProxyNameBySignedRegion();
            assertThat(ec2.describeSubnets().subnets()).extracting(Subnet::subnetId)
                    .containsExactlyInAnyOrderElementsOf(before);
            assertThat(ec2.describeVpcs(request -> request.vpcIds(unrelated.vpcId())).vpcs()).hasSize(1);
        }
    }
}
