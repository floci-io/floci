package io.floci.aws.services.rds;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;

import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.Ec2Requests.defaultSecurityGroupId;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static io.floci.aws.testing.RdsRequests.vpcSecurityGroupIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The VPC security groups of a DB instance, checked through the AWS SDK against a VPC, subnets and
 * groups that EC2 made: {@code CreateDBInstance} and {@code ModifyDBInstance} refuse a group that
 * does not exist or sits in another VPC than the instance's DB subnet group, and keep nothing of the
 * refused request. An instance that names no groups, or moves to another VPC without naming any, gets
 * its VPC's default security group, and an Aurora member, which keeps none, reports it.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsVpcSecurityGroupsIntegrationTest {

    private static final String ACCOUNT = "246813579024";
    private static final String INSTANCE = "vpc-groups-db";
    private static final String CLUSTER = "vpc-groups-cluster";
    private static final String MEMBER = "vpc-groups-member";

    @Test
    void anInstancesGroupsMustExistAndSitInItsVpc() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            String vpcId = ec2.createVpc(request -> request.cidrBlock("10.41.0.0/16")).vpc().vpcId();
            String group = group(ec2, vpcId, "app");
            String otherVpcId = ec2.createVpc(request -> request.cidrBlock("10.42.0.0/16")).vpc().vpcId();
            String otherVpcGroup = group(ec2, otherVpcId, "elsewhere");
            subnetGroup(ec2, rds, "vpc-groups-subnets", vpcId, "10.41");
            subnetGroup(ec2, rds, "vpc-groups-elsewhere", otherVpcId, "10.42");

            AwsServiceException otherVpc = assertAwsError("InvalidParameterCombination", 400,
                    () -> create(rds, List.of(otherVpcGroup)));
            assertEquals("The DB instance and EC2 security group are in different VPCs. The DB instance is in "
                    + vpcId + " and the EC2 security group is in " + otherVpcId, otherVpc.awsErrorDetails().errorMessage());
            assertEquals("Invalid security group , groupId= sg-0123456789abcdef0, groupName=.",
                    assertAwsError("InvalidParameterValue", 400, () -> create(rds, List.of("sg-0123456789abcdef0")))
                            .awsErrorDetails().errorMessage());
            assertThrows(DbInstanceNotFoundException.class,
                    () -> rds.describeDBInstances(request -> request.dbInstanceIdentifier(INSTANCE)));

            // An instance that names no groups gets its VPC's default group.
            create(rds, List.of());
            assertEquals(vpcId, rds.describeDBInstances(request -> request.dbInstanceIdentifier(INSTANCE))
                    .dbInstances().getFirst().dbSubnetGroup().vpcId());
            List<String> defaultGroup = List.of(defaultSecurityGroupId(ec2, vpcId));
            assertEquals(defaultGroup, vpcSecurityGroupIds(rds, INSTANCE));

            assertAwsError("InvalidParameterCombination", 400, () -> rds.modifyDBInstance(request -> request
                    .dbInstanceIdentifier(INSTANCE).vpcSecurityGroupIds(otherVpcGroup)));
            assertEquals(defaultGroup, vpcSecurityGroupIds(rds, INSTANCE));

            rds.modifyDBInstance(request -> request.dbInstanceIdentifier(INSTANCE).vpcSecurityGroupIds(group));
            assertEquals(List.of(group), vpcSecurityGroupIds(rds, INSTANCE));

            // An Aurora member's groups are its cluster's, so it keeps none and reports its VPC's default group.
            rds.createDBCluster(request -> request.dbClusterIdentifier(CLUSTER).engine("aurora-postgresql")
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("vpc-groups-subnets"));
            rds.createDBInstance(request -> request.dbInstanceIdentifier(MEMBER).dbClusterIdentifier(CLUSTER)
                    .engine("aurora-postgresql").dbInstanceClass("db.r5.large"));
            assertEquals(defaultGroup, vpcSecurityGroupIds(rds, MEMBER));

            // Its groups cannot follow it to another VPC, so a move that names none gets that VPC's default group.
            rds.modifyDBInstance(request -> request.dbInstanceIdentifier(INSTANCE)
                    .dbSubnetGroupName("vpc-groups-elsewhere"));
            assertEquals(List.of(defaultSecurityGroupId(ec2, otherVpcId)), vpcSecurityGroupIds(rds, INSTANCE));
        }
    }

    /** DB subnet group {@code name} over two new subnets of {@code vpcId}, {@code prefix}.1.0/24 and .2.0/24. */
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

    /** Creates the instance on DB subnet group vpc-groups-subnets, naming {@code groups} when there are any. */
    private static void create(RdsClient rds, List<String> groups) {
        rds.createDBInstance(request -> {
            request.dbInstanceIdentifier(INSTANCE).engine("postgres").dbInstanceClass("db.t3.micro")
                    .allocatedStorage(20).masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("vpc-groups-subnets");
            if (!groups.isEmpty()) {
                request.vpcSecurityGroupIds(groups);
            }
        });
    }
}
