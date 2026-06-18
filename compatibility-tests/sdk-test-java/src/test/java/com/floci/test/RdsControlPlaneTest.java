package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbSubnetGroupResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static RdsClient rds;
    private static String subnetGroupName;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
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
                .subnetIds("subnet-a", "subnet-b"));

        assertThat(createResponse.dbSubnetGroup().subnets())
                .extracting("subnetIdentifier")
                .containsExactly("subnet-a", "subnet-b");

        DescribeDbSubnetGroupsResponse describeResponse = rds.describeDBSubnetGroups(b -> b
                .dbSubnetGroupName(subnetGroupName));

        assertThat(describeResponse.dbSubnetGroups()).hasSize(1);
        assertThat(describeResponse.dbSubnetGroups().get(0).subnets())
                .extracting("subnetIdentifier")
                .containsExactly("subnet-a", "subnet-b");
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
