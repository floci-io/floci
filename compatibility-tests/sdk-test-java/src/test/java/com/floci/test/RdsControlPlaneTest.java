package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbSubnetGroupResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static RdsClient rds;
    private static String subnetGroupName;
    private static List<String> subnetIds;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSubnetsResponse response = ec2.describeSubnets();
            subnetIds = response.subnets().stream()
                    .map(subnet -> subnet.subnetId())
                    .sorted()
                    .limit(2)
                    .toList();
        }
        assertThat(subnetIds).hasSizeGreaterThanOrEqualTo(2);
    }

    @AfterAll
    static void cleanup() {
        if (rds != null) {
            try {
                rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(subnetGroupName));
            } catch (Exception ignored) {
            }
            rds.close();
        }
    }

    @Test
    void sdkUnmarshalsDbSubnetGroupSubnets() {
        CreateDbSubnetGroupResponse createResponse = rds.createDBSubnetGroup(b -> b
                .dbSubnetGroupName(subnetGroupName)
                .dbSubnetGroupDescription("SDK subnet group shape")
                .subnetIds(subnetIds));

        assertThat(createResponse.dbSubnetGroup().subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);

        DescribeDbSubnetGroupsResponse describeResponse = rds.describeDBSubnetGroups(b -> b
                .dbSubnetGroupName(subnetGroupName));

        assertThat(describeResponse.dbSubnetGroups()).hasSize(1);
        assertThat(describeResponse.dbSubnetGroups().get(0).subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);
    }

    @Test
    void sdkDiscoversCurrentSmallGravitonPostgresOption() {
        DescribeOrderableDbInstanceOptionsResponse response = rds.describeOrderableDBInstanceOptions(b -> b
                .engine("postgres")
                .engineVersion("16.14")
                .dbInstanceClass("db.t4g.small"));

        assertThat(response.orderableDBInstanceOptions()).hasSize(1);
        assertThat(response.orderableDBInstanceOptions().get(0).engine()).isEqualTo("postgres");
        assertThat(response.orderableDBInstanceOptions().get(0).engineVersion()).isEqualTo("16.14");
        assertThat(response.orderableDBInstanceOptions().get(0).dbInstanceClass()).isEqualTo("db.t4g.small");
    }
}
