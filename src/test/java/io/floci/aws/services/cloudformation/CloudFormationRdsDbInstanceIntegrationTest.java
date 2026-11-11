package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership;

import java.util.List;

import static io.floci.aws.testing.CfnStackWaits.output;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VPCSecurityGroups} and {@code StorageEncrypted} on {@code AWS::RDS::DBInstance}, read back
 * through the AWS SDK: the groups follow the template in place, through a failed update and when the
 * template drops them, and a change of {@code StorageEncrypted} either way replaces the instance.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsDbInstanceIntegrationTest {

    private final String stackName = "cfn-rds-db-" + Long.toString(System.nanoTime(), 36);
    private CloudFormationClient cloudFormation;
    private RdsClient rds;

    @BeforeEach
    void clients() {
        cloudFormation = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        rds = AwsTestClients.create(RdsClient.builder(), "test", Region.US_EAST_1);
    }

    @AfterEach
    void deleteStack() {
        try {
            CfnStackWaits.deleteStack(stackName);
        } finally {
            cloudFormation.close();
            rds.close();
        }
    }

    @Test
    void securityGroupsFollowTheTemplateThroughChangeRollbackAndRemoval() {
        Stack created = create(template("First", false, false));
        String id = output(created, "DbId");
        assertEquals(List.of(output(created, "FirstId")), groups(id));

        Stack changed = update(template("Second", false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(changed, "DbId"));
        assertEquals(List.of(output(changed, "SecondId")), groups(id));

        update(template("First", false, true), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(output(changed, "SecondId")), groups(id));

        // As AWS's handler does, a template that drops the groups puts the instance on its VPC's default group.
        Stack cleared = update(template(null, false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(cleared, "DbId"));
        assertEquals(List.of(defaultGroupOf(instance(id).dbSubnetGroup().vpcId())), groups(id));
    }

    @Test
    void aStorageEncryptedChangeReplacesTheInstanceAndAFailedOneKeepsIt() {
        String id = output(create(template(null, true, false)), "DbId");
        assertTrue(instance(id).storageEncrypted());

        // Dropping StorageEncrypted asks for an unencrypted instance, which only a new one can be.
        update(template(null, false, true), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(id), stackInstanceIds(), "the replacement is deleted again");
        assertTrue(instance(id).storageEncrypted());

        String unencrypted = output(update(template(null, false, false), "UPDATE_COMPLETE"), "DbId");
        assertNotEquals(id, unencrypted);
        assertFalse(instance(unencrypted).storageEncrypted());
        assertEquals(List.of(unencrypted), stackInstanceIds(), "the prior instance is deleted once the update commits");

        String encrypted = output(update(template(null, true, false), "UPDATE_COMPLETE"), "DbId");
        assertNotEquals(unencrypted, encrypted);
        assertTrue(instance(encrypted).storageEncrypted());
        assertEquals(List.of(encrypted), stackInstanceIds());
    }

    private Stack create(String template) {
        return CfnStackWaits.createStack(cloudFormation, stackName, template, "CREATE_COMPLETE");
    }

    private Stack update(String template, String expectedStatus) {
        return CfnStackWaits.updateStack(cloudFormation, stackName, template, expectedStatus);
    }

    /**
     * Two security groups and a DB instance on {@code group}, First or Second, or on none when it is
     * null; with {@code StorageEncrypted: true} when {@code encrypted}, and with a resource that fails
     * after the instance when {@code laterFails}.
     */
    private static String template(String group, boolean encrypted, boolean laterFails) {
        String groups = group == null ? "" : """
                , "VPCSecurityGroups": [{"Fn::GetAtt": ["%s", "GroupId"]}]""".formatted(group);
        return """
                {"Resources": {
                  "First": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "first"}},
                  "Second": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "second"}},
                  "Db": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "postgres",
                    "DBInstanceClass": "db.t3.micro", "AllocatedStorage": 20, "MasterUsername": "dbadmin",
                    "MasterUserPassword": "password123"%s%s}}%s
                }, "Outputs": {
                  "DbId": {"Value": {"Ref": "Db"}},
                  "FirstId": {"Value": {"Fn::GetAtt": ["First", "GroupId"]}},
                  "SecondId": {"Value": {"Fn::GetAtt": ["Second", "GroupId"]}}
                }}
                """.formatted(groups, encrypted ? ", \"StorageEncrypted\": true" : "",
                laterFails ? CfnStackWaits.failingResourceAfter("Db") : "");
    }

    private DBInstance instance(String id) {
        return rds.describeDBInstances(request -> request.dbInstanceIdentifier(id)).dbInstances().getFirst();
    }

    private List<String> groups(String id) {
        return instance(id).vpcSecurityGroups().stream().map(VpcSecurityGroupMembership::vpcSecurityGroupId).toList();
    }

    /** The instances this stack's {@code Db} resource has, by the name CloudFormation generates for it. */
    private List<String> stackInstanceIds() {
        return rds.describeDBInstances().dbInstances().stream().map(DBInstance::dbInstanceIdentifier)
                .filter(id -> id.startsWith(stackName + "-db-")).toList();
    }

    private static String defaultGroupOf(String vpcId) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            return ec2.describeSecurityGroups(request -> request.filters(
                    filter -> filter.name("vpc-id").values(vpcId),
                    filter -> filter.name("group-name").values("default")))
                    .securityGroups().getFirst().groupId();
        }
    }
}
