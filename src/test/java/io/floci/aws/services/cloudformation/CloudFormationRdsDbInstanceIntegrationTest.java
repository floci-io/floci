package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-rds-db");

    @Test
    void securityGroupsFollowTheTemplateThroughChangeRollbackAndRemoval() {
        Stack created = stack.create(template("First", false, false));
        String id = output(created, "DbId");
        assertEquals(List.of(output(created, "FirstId")), groups(id));

        Stack changed = stack.update(template("Second", false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(changed, "DbId"));
        assertEquals(List.of(output(changed, "SecondId")), groups(id));

        stack.update(template("First", false, true), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(output(changed, "SecondId")), groups(id));

        // As AWS's handler does, a template that drops the groups puts the instance on its VPC's default group.
        Stack cleared = stack.update(template(null, false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(cleared, "DbId"));
        assertEquals(List.of(defaultGroupOf(instance(id).dbSubnetGroup().vpcId())), groups(id));
    }

    @Test
    void aStorageEncryptedChangeReplacesTheInstanceAndAFailedOneKeepsIt() {
        String id = output(stack.create(template(null, true, false)), "DbId");
        assertTrue(instance(id).storageEncrypted());

        // Dropping StorageEncrypted asks for an unencrypted instance, which only a new one can be.
        stack.update(template(null, false, true), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(id), stackInstanceIds(), "the replacement is deleted again");
        assertTrue(instance(id).storageEncrypted());

        String unencrypted = output(stack.update(template(null, false, false), "UPDATE_COMPLETE"), "DbId");
        assertNotEquals(id, unencrypted);
        assertFalse(instance(unencrypted).storageEncrypted());
        assertEquals(List.of(unencrypted), stackInstanceIds(), "the prior instance is deleted once the update commits");

        String encrypted = output(stack.update(template(null, true, false), "UPDATE_COMPLETE"), "DbId");
        assertNotEquals(unencrypted, encrypted);
        assertTrue(instance(encrypted).storageEncrypted());
        assertEquals(List.of(encrypted), stackInstanceIds());
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

    private RdsClient rds() {
        return stack.client(RdsClient.builder());
    }

    private DBInstance instance(String id) {
        return rds().describeDBInstances(request -> request.dbInstanceIdentifier(id)).dbInstances().getFirst();
    }

    private List<String> groups(String id) {
        return instance(id).vpcSecurityGroups().stream().map(VpcSecurityGroupMembership::vpcSecurityGroupId).toList();
    }

    /** The instances this stack's {@code Db} resource has, by the name CloudFormation generates for it. */
    private List<String> stackInstanceIds() {
        return rds().describeDBInstances().dbInstances().stream()
                .map(DBInstance::dbInstanceIdentifier)
                .filter(id -> id.startsWith(stack.name() + "-db-")).toList();
    }

    private String defaultGroupOf(String vpcId) {
        return stack.client(Ec2Client.builder()).describeSecurityGroups(request -> request.filters(
                filter -> filter.name("vpc-id").values(vpcId),
                filter -> filter.name("group-name").values("default")))
                .securityGroups().getFirst().groupId();
    }
}
