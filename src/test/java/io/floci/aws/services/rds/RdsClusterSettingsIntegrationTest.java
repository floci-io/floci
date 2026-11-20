package io.floci.aws.services.rds;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DbClusterNotFoundException;
import software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership;

import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.Ec2Requests.defaultSecurityGroupId;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maintenance windows, VPC security groups, Enhanced Monitoring, Performance Insights and
 * Database Insights settings of a DB cluster round-trip from CreateDBCluster and ModifyDBCluster to
 * DescribeDBClusters. They used to be dropped, and the response reported fixed windows and
 * {@code sg-00000000}, so the Terraform AWS provider planned the same change on every run.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterSettingsIntegrationTest {

    private static final String ACCOUNT = "135792468024";
    private static final String CLUSTER = "settings-cluster";
    private static final String DEFAULTS = "settings-defaults";
    private static final String REFUSED = "settings-refused";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/rds-monitoring";

    @Test
    void clusterSettingsRoundTripThroughCreateAndModify() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            String vpcId = ec2.createVpc(request -> request.cidrBlock("10.51.0.0/16")).vpc().vpcId();
            String app = group(ec2, vpcId, "app");
            String other = group(ec2, vpcId, "other");
            String elsewhereVpc = ec2.createVpc(request -> request.cidrBlock("10.52.0.0/16")).vpc().vpcId();
            String elsewhere = group(ec2, elsewhereVpc, "elsewhere");
            subnetGroup(ec2, rds, "settings-subnets", vpcId, "10.51");

            // A cluster that names none of them takes the AWS defaults.
            rds.createDBCluster(request -> request.dbClusterIdentifier(DEFAULTS).engine("aurora-postgresql")
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("settings-subnets"));
            DBCluster defaults = describe(rds, DEFAULTS);
            assertEquals("04:00-06:00", defaults.preferredBackupWindow());
            assertEquals("mon:00:00-mon:03:00", defaults.preferredMaintenanceWindow());
            assertEquals(List.of(defaultSecurityGroupId(ec2, vpcId)), groups(defaults));
            assertEquals(0, defaults.monitoringInterval());
            assertFalse(defaults.performanceInsightsEnabled());
            assertEquals("standard", defaults.databaseInsightsModeAsString());

            rds.createDBCluster(request -> request.dbClusterIdentifier(CLUSTER).engine("aurora-postgresql")
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("settings-subnets").vpcSecurityGroupIds(app)
                    .preferredBackupWindow("03:00-06:00").preferredMaintenanceWindow("Sun:23:00-Mon:02:00")
                    .monitoringInterval(60).monitoringRoleArn(ROLE)
                    .enablePerformanceInsights(true).performanceInsightsRetentionPeriod(558)
                    .databaseInsightsMode("advanced"));
            DBCluster created = describe(rds, CLUSTER);
            assertEquals("03:00-06:00", created.preferredBackupWindow());
            assertEquals("sun:23:00-mon:02:00", created.preferredMaintenanceWindow());
            assertEquals(List.of(app), groups(created));
            assertEquals(60, created.monitoringInterval());
            assertEquals(ROLE, created.monitoringRoleArn());
            assertTrue(created.performanceInsightsEnabled());
            assertEquals(558, created.performanceInsightsRetentionPeriod());
            assertEquals("advanced", created.databaseInsightsModeAsString());

            // A modify changes what it names and keeps the rest.
            rds.modifyDBCluster(request -> request.dbClusterIdentifier(CLUSTER).applyImmediately(true)
                    .vpcSecurityGroupIds(other).preferredBackupWindow("06:30-07:30"));
            DBCluster modified = describe(rds, CLUSTER);
            assertEquals("06:30-07:30", modified.preferredBackupWindow());
            assertEquals("sun:23:00-mon:02:00", modified.preferredMaintenanceWindow());
            assertEquals(List.of(other), groups(modified));
            assertEquals(60, modified.monitoringInterval());
            assertEquals(558, modified.performanceInsightsRetentionPeriod());

            rds.modifyDBCluster(request -> request.dbClusterIdentifier(CLUSTER).applyImmediately(true)
                    .databaseInsightsMode("standard").enablePerformanceInsights(false).monitoringInterval(0));
            DBCluster disabled = describe(rds, CLUSTER);
            assertEquals("standard", disabled.databaseInsightsModeAsString());
            assertFalse(disabled.performanceInsightsEnabled());
            assertNull(disabled.performanceInsightsRetentionPeriod());
            assertEquals(0, disabled.monitoringInterval());

            // Refused requests change nothing and leave no cluster behind.
            assertAwsError("InvalidParameterCombination", 400, () -> rds.modifyDBCluster(request -> request
                    .dbClusterIdentifier(CLUSTER).vpcSecurityGroupIds(elsewhere)));
            assertAwsError("InvalidParameterValue", 400, () -> rds.modifyDBCluster(request -> request
                    .dbClusterIdentifier(CLUSTER).preferredBackupWindow("00:30-01:30")
                    .preferredMaintenanceWindow("mon:00:00-mon:01:00")));
            assertAwsError("InvalidParameterValue", 400, () -> rds.modifyDBCluster(request -> request
                    .dbClusterIdentifier(CLUSTER).monitoringInterval(7)));
            assertEquals(List.of(other), groups(describe(rds, CLUSTER)));
            assertEquals("06:30-07:30", describe(rds, CLUSTER).preferredBackupWindow());

            assertAwsError("InvalidParameterCombination", 400, () -> rds.createDBCluster(request -> request
                    .dbClusterIdentifier(REFUSED).engine("aurora-postgresql")
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("settings-subnets").vpcSecurityGroupIds(elsewhere)));
            assertAwsError("InvalidParameterCombination", 400, () -> rds.createDBCluster(request -> request
                    .dbClusterIdentifier(REFUSED).engine("aurora-postgresql")
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .monitoringInterval(60)));
            assertThrows(DbClusterNotFoundException.class, () -> describe(rds, REFUSED));
        }
    }

    private static DBCluster describe(RdsClient rds, String id) {
        return rds.describeDBClusters(request -> request.dbClusterIdentifier(id)).dbClusters().getFirst();
    }

    private static List<String> groups(DBCluster cluster) {
        return cluster.vpcSecurityGroups().stream().map(VpcSecurityGroupMembership::vpcSecurityGroupId).toList();
    }

    private static void subnetGroup(Ec2Client ec2, RdsClient rds, String name, String vpcId, String prefix) {
        List<String> subnetIds = List.of(subnet(ec2, vpcId, prefix + ".1.0/24", "us-east-1a"),
                subnet(ec2, vpcId, prefix + ".2.0/24", "us-east-1b"));
        rds.createDBSubnetGroup(request -> request.dbSubnetGroupName(name).dbSubnetGroupDescription(name)
                .subnetIds(subnetIds));
    }

    private static String subnet(Ec2Client ec2, String vpcId, String cidr, String zone) {
        return ec2.createSubnet(request -> request.vpcId(vpcId).cidrBlock(cidr).availabilityZone(zone))
                .subnet().subnetId();
    }

    private static String group(Ec2Client ec2, String vpcId, String name) {
        return ec2.createSecurityGroup(request -> request.vpcId(vpcId).groupName(name).description(name)).groupId();
    }
}
