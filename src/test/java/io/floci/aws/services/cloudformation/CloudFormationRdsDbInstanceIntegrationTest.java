package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.util.List;

import static io.floci.aws.testing.CfnStackWaits.failedResourceReason;
import static io.floci.aws.testing.CfnStackWaits.output;
import static io.floci.aws.testing.Ec2Requests.defaultSecurityGroupId;
import static io.floci.aws.testing.RdsRequests.auroraClusterWithMember;
import static io.floci.aws.testing.RdsRequests.vpcSecurityGroupIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VPCSecurityGroups} and {@code StorageEncrypted} on {@code AWS::RDS::DBInstance}, read back
 * through the AWS SDK: the groups follow the template in place, through a failed update, a group of
 * another VPC that RDS refuses, and a template that drops them, and a change of {@code StorageEncrypted}
 * either way replaces the instance. A cluster member's groups are its cluster's, so an update that
 * changes them fails, even when the template does so only through a parameter or a replaced group.
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
        assertEquals(List.of(output(created, "FirstId")), vpcSecurityGroupIds(rds(), id));

        Stack changed = stack.update(template("Second", false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(changed, "DbId"));
        assertEquals(List.of(output(changed, "SecondId")), vpcSecurityGroupIds(rds(), id));

        stack.update(template("First", false, true), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(output(changed, "SecondId")), vpcSecurityGroupIds(rds(), id));

        // RDS refuses a group of another VPC, so the update rolls back and the instance keeps its group.
        stack.update(template("Outside", false, false), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(List.of(output(changed, "SecondId")), vpcSecurityGroupIds(rds(), id));

        // As AWS's handler does, a template that drops the groups puts the instance on its VPC's default group.
        Stack cleared = stack.update(template(null, false, false), "UPDATE_COMPLETE");
        assertEquals(id, output(cleared, "DbId"));
        assertEquals(List.of(defaultSecurityGroupId(stack.client(Ec2Client.builder()),
                instance(id).dbSubnetGroup().vpcId())), vpcSecurityGroupIds(rds(), id));
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

    @ParameterizedTest
    @CsvSource({"Second, first, UPDATE_ROLLBACK_COMPLETE", "First, replaced, UPDATE_ROLLBACK_FAILED"})
    void aMembersGroupsChangedOnlyThroughAParameterOrAReplacedGroupFailTheUpdate(String group, String firstDescription,
                                                                               String status) {
        String id = output(stack.create(memberTemplate("First", "first")), "MemberId");
        List<String> groups = vpcSecurityGroupIds(rds(), id);

        // A replaced security group is not rolled back, so that update's rollback fails at First.
        stack.update(memberTemplate(group, firstDescription), status);
        String reason = failedResourceReason(stack.name());
        assertTrue(reason.contains("Modify vpc security group for the DB Cluster"), reason);
        assertEquals(groups, vpcSecurityGroupIds(rds(), id));
    }

    /**
     * The {@link #securityGroups}, First with {@code firstDescription}, which replaces it when it
     * changes, and an Aurora cluster with a member on the group the parameter {@code Group} names, {@code group}
     * by default. The member's own properties never change.
     */
    private static String memberTemplate(String group, String firstDescription) {
        return """
                {"Parameters": {"Group": {"Type": "String", "Default": "%s"}},
                 "Conditions": {"OnSecond": {"Fn::Equals": [{"Ref": "Group"}, "Second"]}},
                 "Resources": {%s, %s},
                 "Outputs": {"MemberId": {"Value": {"Ref": "Member"}}}}
                """.formatted(group, securityGroups(firstDescription), auroraClusterWithMember("""
                "VPCSecurityGroups": [{"Fn::If": ["OnSecond",
                  {"Fn::GetAtt": ["Second", "GroupId"]}, {"Fn::GetAtt": ["First", "GroupId"]}]}]"""));
    }

    /** Two security groups of the default VPC: First, described as {@code firstDescription}, and Second. */
    private static String securityGroups(String firstDescription) {
        return """
                "First": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "%s"}},
                "Second": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "second"}}"""
                .formatted(firstDescription);
    }

    /**
     * The {@link #securityGroups}, a third, Outside, of another VPC, and a DB instance on
     * {@code group}, or on none when it is null; with {@code StorageEncrypted: true} when {@code encrypted}, and with a resource that fails
     * after the instance when {@code laterFails}.
     */
    private static String template(String group, boolean encrypted, boolean laterFails) {
        String groups = group == null ? "" : """
                , "VPCSecurityGroups": [{"Fn::GetAtt": ["%s", "GroupId"]}]""".formatted(group);
        return """
                {"Resources": {%s,
                  "Elsewhere": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.43.0.0/16"}},
                  "Outside": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "outside",
                    "VpcId": {"Ref": "Elsewhere"}}},
                  "Db": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "postgres",
                    "DBInstanceClass": "db.t3.micro", "AllocatedStorage": 20, "MasterUsername": "dbadmin",
                    "MasterUserPassword": "password123"%s%s}}%s
                }, "Outputs": {
                  "DbId": {"Value": {"Ref": "Db"}},
                  "FirstId": {"Value": {"Fn::GetAtt": ["First", "GroupId"]}},
                  "SecondId": {"Value": {"Fn::GetAtt": ["Second", "GroupId"]}}
                }}
                """.formatted(securityGroups("first"), groups, encrypted ? ", \"StorageEncrypted\": true" : "",
                laterFails ? CfnStackWaits.failingResourceAfter("Db") : "");
    }

    private RdsClient rds() {
        return stack.client(RdsClient.builder());
    }

    private DBInstance instance(String id) {
        return rds().describeDBInstances(request -> request.dbInstanceIdentifier(id)).dbInstances().getFirst();
    }

    /** The instances this stack's {@code Db} resource has, by the name CloudFormation generates for it. */
    private List<String> stackInstanceIds() {
        return rds().describeDBInstances().dbInstances().stream()
                .map(DBInstance::dbInstanceIdentifier)
                .filter(id -> id.startsWith(stack.name() + "-db-")).toList();
    }
}
