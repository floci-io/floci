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
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static RdsClient rds;
    private static String subnetGroupName;
    private static String serverlessClusterName;
    private static String standardClusterName;
    private static List<String> subnetIds;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
        serverlessClusterName = TestFixtures.uniqueName("rds-serverless");
        standardClusterName = TestFixtures.uniqueName("rds-standard");
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
                rds.deleteDBCluster(b -> b
                        .dbClusterIdentifier(serverlessClusterName)
                        .skipFinalSnapshot(true));
            } catch (Exception e) {
                System.err.println("Unable to delete the RDS scaling compatibility-test cluster: "
                        + e.getMessage());
            }
            try {
                rds.deleteDBCluster(b -> b
                        .dbClusterIdentifier(standardClusterName)
                        .skipFinalSnapshot(true));
            } catch (Exception e) {
                System.err.println("Unable to delete the RDS standard compatibility-test cluster: "
                        + e.getMessage());
            }
            try {
                rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(subnetGroupName));
            } catch (Exception e) {
                System.err.println("Unable to delete the RDS compatibility-test subnet group: "
                        + e.getMessage());
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

    @Test
    void sdkRoundTripsServerlessV2ScalingAndAutoPause() {
        var created = rds.createDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .engine("aurora-postgresql")
                .masterUsername("admin")
                .masterUserPassword("password")
                .serverlessV2ScalingConfiguration(c -> c
                        .minCapacity(0.0)
                        .maxCapacity(16.0)));

        assertThat(created.dbCluster().engine()).isEqualTo("aurora-postgresql");
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.0);
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().maxCapacity())
                .isEqualTo(16.0);
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(300);

        var modified = rds.modifyDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .serverlessV2ScalingConfiguration(c -> c
                        .secondsUntilAutoPause(600)));

        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.0);
        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().maxCapacity())
                .isEqualTo(16.0);
        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(600);

        var widerRange = rds.modifyDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .serverlessV2ScalingConfiguration(c -> c.maxCapacity(32.0)));

        assertThat(widerRange.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.0);
        assertThat(widerRange.dbCluster().serverlessV2ScalingConfiguration().maxCapacity())
                .isEqualTo(32.0);
        assertThat(widerRange.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(600);

        var alwaysActive = rds.modifyDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .serverlessV2ScalingConfiguration(c -> c.minCapacity(0.5)));

        assertThat(alwaysActive.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.5);
        assertThat(alwaysActive.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isNull();

        var described = rds.describeDBClusters(b -> b.dbClusterIdentifier(serverlessClusterName))
                .dbClusters()
                .get(0);
        assertThat(described.engine()).isEqualTo("aurora-postgresql");
        assertThat(described.serverlessV2ScalingConfiguration().minCapacity()).isEqualTo(0.5);
        assertThat(described.serverlessV2ScalingConfiguration().maxCapacity()).isEqualTo(32.0);
        assertThat(described.serverlessV2ScalingConfiguration().secondsUntilAutoPause()).isNull();
    }

    @Test
    void sdkRejectsServerlessV2ScalingForNonAuroraClusters() {
        assertThatThrownBy(() -> rds.createDBCluster(b -> b
                .dbClusterIdentifier(standardClusterName)
                .engine("postgres")
                .masterUsername("admin")
                .masterUserPassword("password")
                .serverlessV2ScalingConfiguration(c -> c
                        .minCapacity(0.5)
                        .maxCapacity(16.0))))
                .isInstanceOfSatisfying(RdsException.class, exception ->
                        assertInvalidServerlessEngine(exception));

        var created = rds.createDBCluster(b -> b
                .dbClusterIdentifier(standardClusterName)
                .engine("postgres")
                .masterUsername("admin")
                .masterUserPassword("password"));
        assertThat(created.dbCluster().engine()).isEqualTo("postgres");

        assertThatThrownBy(() -> rds.modifyDBCluster(b -> b
                .dbClusterIdentifier(standardClusterName)
                .serverlessV2ScalingConfiguration(c -> c
                        .minCapacity(0.5)
                        .maxCapacity(16.0))))
                .isInstanceOfSatisfying(RdsException.class, exception ->
                        assertInvalidServerlessEngine(exception));
    }

    private static void assertInvalidServerlessEngine(RdsException exception) {
        assertThat(exception.statusCode()).isEqualTo(400);
        assertThat(exception.awsErrorDetails().errorCode())
                .isEqualTo("InvalidParameterCombination");
        assertThat(exception.awsErrorDetails().errorMessage())
                .isEqualTo("Parameters that must not be used together were used together. "
                        + "Remove one of the conflicting parameters and try again.");
    }
}
