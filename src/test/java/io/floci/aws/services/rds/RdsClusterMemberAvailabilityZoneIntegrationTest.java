package io.floci.aws.services.rds;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbClusterNotFoundException;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DbSubnetGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.Subnet;

import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An Aurora member created with an {@code AvailabilityZone} is placed in that zone, which must be one
 * of its cluster's DB subnet group, and {@code DescribeDBInstances} reports it.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterMemberAvailabilityZoneIntegrationTest {

    private static final String ACCOUNT = "975318642024";
    private static final String CLUSTER = "member-az-cluster";
    private static final List<String> MEMBERS = List.of("member-az-a", "member-az-b", "member-az-c");

    @AfterEach
    void cleanUp() {
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            for (String member : MEMBERS) {
                try {
                    rds.deleteDBInstance(request -> request.dbInstanceIdentifier(member).skipFinalSnapshot(true));
                } catch (DbInstanceNotFoundException ignored) {
                    // the test never created this member, or it was refused
                }
            }
            try {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(CLUSTER).skipFinalSnapshot(true));
            } catch (DbClusterNotFoundException ignored) {
                // the test failed before it created the cluster
            }
            try {
                rds.deleteDBSubnetGroup(request -> request.dbSubnetGroupName(CLUSTER));
            } catch (DbSubnetGroupNotFoundException ignored) {
                // the test failed before it created the subnet group
            }
        }
    }

    @Test
    void aMemberIsPlacedInTheZoneItNames() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            cluster(ec2, rds);

            member(rds, "member-az-a", "us-east-1a");
            member(rds, "member-az-b", "us-east-1b");

            assertEquals("us-east-1a", zone(rds, "member-az-a"));
            assertEquals("us-east-1b", zone(rds, "member-az-b"));
        }
    }

    @Test
    void aZoneOutsideTheSubnetGroupIsRefused() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            cluster(ec2, rds);

            assertAwsError("InvalidVPCNetworkStateFault", 400, () -> member(rds, "member-az-c", "us-east-1c"));
            assertThrows(DbInstanceNotFoundException.class,
                    () -> rds.describeDBInstances(request -> request.dbInstanceIdentifier("member-az-c")));
        }
    }

    @Test
    void aZoneAddedToTheSubnetGroupAfterTheClusterIsAccepted() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            String vpcId = cluster(ec2, rds);
            List<String> subnetIds = new ArrayList<>(rds.describeDBSubnetGroups(request -> request
                    .dbSubnetGroupName(CLUSTER)).dbSubnetGroups().getFirst().subnets().stream()
                    .map(Subnet::subnetIdentifier).toList());
            subnetIds.add(subnet(ec2, vpcId, "10.61.3.0/24", "us-east-1c"));
            rds.modifyDBSubnetGroup(request -> request.dbSubnetGroupName(CLUSTER).subnetIds(subnetIds));

            member(rds, "member-az-c", "us-east-1c");

            assertEquals("us-east-1c", zone(rds, "member-az-c"));
        }
    }

    /** The cluster on a DB subnet group over one subnet in us-east-1a and one in us-east-1b; returns its VPC. */
    private static String cluster(Ec2Client ec2, RdsClient rds) {
        String vpcId = ec2.createVpc(request -> request.cidrBlock("10.61.0.0/16")).vpc().vpcId();
        List<String> subnetIds = List.of(subnet(ec2, vpcId, "10.61.1.0/24", "us-east-1a"),
                subnet(ec2, vpcId, "10.61.2.0/24", "us-east-1b"));
        rds.createDBSubnetGroup(request -> request.dbSubnetGroupName(CLUSTER).dbSubnetGroupDescription(CLUSTER)
                .subnetIds(subnetIds));
        rds.createDBCluster(request -> request.dbClusterIdentifier(CLUSTER).engine("aurora-postgresql")
                .masterUsername("dbadmin").masterUserPassword("password123").dbSubnetGroupName(CLUSTER));
        return vpcId;
    }

    private static String subnet(Ec2Client ec2, String vpcId, String cidr, String zone) {
        return ec2.createSubnet(request -> request.vpcId(vpcId).cidrBlock(cidr).availabilityZone(zone))
                .subnet().subnetId();
    }

    private static void member(RdsClient rds, String id, String zone) {
        rds.createDBInstance(request -> request.dbInstanceIdentifier(id).dbClusterIdentifier(CLUSTER)
                .engine("aurora-postgresql").dbInstanceClass("db.r5.large").availabilityZone(zone));
    }

    private static String zone(RdsClient rds, String id) {
        return rds.describeDBInstances(request -> request.dbInstanceIdentifier(id))
                .dbInstances().getFirst().availabilityZone();
    }
}
