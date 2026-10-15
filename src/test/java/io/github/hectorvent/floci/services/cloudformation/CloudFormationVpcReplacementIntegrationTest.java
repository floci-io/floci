package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle of {@code AWS::EC2::VPC} beyond its first create: a changed CidrBlock replaces the
 * VPC and the displaced one (with the subnet it held) is removed once the update commits, an
 * in-place tag and DNS change next to a resource that fails the update is rolled back, DeleteStack
 * removes the VPC together with its subnet, and an instance launched into the subnet out of band
 * fails that delete with {@code DependencyViolation} until it is terminated.
 */
@QuarkusTest
class CloudFormationVpcReplacementIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-3/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-3/ec2/aws4_request";

    /** A VPC and a subnet inside it; the subnet's CIDR follows the VPC's second octet. */
    private static String template(String octet, String name) {
        return """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {
                        "CidrBlock": "10.%1$s.0.0/16",
                        "Tags": [{"Key": "Name", "Value": "%2$s"}]
                      }
                    },
                    "Subnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.%1$s.1.0/24"}
                    }
                  },
                  "Outputs": {
                    "VpcId": {"Value": {"Ref": "Vpc"}},
                    "SubnetId": {"Value": {"Ref": "Subnet"}}
                  }
                }
                """.formatted(octet, name);
    }

    /**
     * A VPC alone; the second form flips the mutable properties and adds a resource that cannot be
     * created. Boom depends on the VPC so its failure lands after the in-place change was written.
     */
    private static String rollbackTemplate(boolean changed) {
        String failing = changed
                ? ",\n    \"Boom\": {\"Type\": \"AWS::SecretsManager::Secret\", \"DependsOn\": \"Vpc\","
                  + " \"Properties\": {\"SecretString\": \"x\", \"GenerateSecretString\": {\"SecretStringTemplate\": \"{}\", \"GenerateStringKey\": \"p\"}}}"
                : "";
        return """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {
                        "CidrBlock": "10.47.0.0/16",
                        "EnableDnsHostnames": %1$s,
                        "Tags": [{"Key": "Name", "Value": "%2$s"}]
                      }
                    }%3$s
                  },
                  "Outputs": {"VpcId": {"Value": {"Ref": "Vpc"}}}
                }
                """.formatted(changed ? "false" : "true", changed ? "after" : "before", failing);
    }

    @Test
    void aChangedCidrBlockReplacesTheVpcAndTheDisplacedOneIsRemovedOnceTheUpdateCommits() {
        String stack = "cfn-vpc-replace-" + Long.toString(System.nanoTime(), 36);
        cloudFormation(stack, "CreateStack", template("45", "first"));
        Map<String, String> before = outputs(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", template("46", "second"));

        Map<String, String> after = outputs(stack, "UPDATE_COMPLETE");
        assertFalse(before.get("VpcId").equals(after.get("VpcId")), "a changed CidrBlock must replace the VPC");
        assertFalse(before.get("SubnetId").equals(after.get("SubnetId")), "the subnet follows its VPC");
        String vpcs = ec2("DescribeVpcs");
        assertFalse(vpcs.contains(before.get("VpcId")), "the displaced VPC must be gone: " + vpcs);
        assertTrue(vpcs.contains(after.get("VpcId")));
        String replacement = ec2("DescribeVpcs", Map.of("VpcId.1", after.get("VpcId")));
        assertTrue(replacement.contains("<cidrBlock>10.46.0.0/16</cidrBlock>"), replacement);
        assertTrue(replacement.contains("<key>Name</key>") && replacement.contains("<value>second</value>"),
                "the replacement carries the stack's tags: " + replacement);
        assertFalse(ec2("DescribeSubnets").contains(before.get("SubnetId")), "the displaced subnet must be gone");

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
        assertFalse(ec2("DescribeVpcs").contains(after.get("VpcId")), "stack delete must remove the VPC");
        assertFalse(ec2("DescribeSubnets").contains(after.get("SubnetId")), "stack delete must remove the subnet");
    }

    @Test
    void anInPlaceChangeNextToAFailingResourceIsRolledBack() {
        String stack = "cfn-vpc-rollback-" + Long.toString(System.nanoTime(), 36);
        cloudFormation(stack, "CreateStack", rollbackTemplate(false));
        String vpcId = outputs(stack, "CREATE_COMPLETE").get("VpcId");
        assertTrue(describeVpc(vpcId).contains("<value>before</value>"));
        assertEquals("true", dnsHostnames(vpcId));

        cloudFormation(stack, "UpdateStack", rollbackTemplate(true));

        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        String vpc = describeVpc(vpcId);
        assertTrue(vpc.contains("<value>before</value>") && !vpc.contains("<value>after</value>"),
                "the VPC's prior tag must be back: " + vpc);
        assertEquals("true", dnsHostnames(vpcId), "the VPC's prior EnableDnsHostnames must be back");

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
        assertFalse(ec2("DescribeVpcs").contains(vpcId), "stack delete must remove the VPC");
    }

    @Test
    void anInstanceLaunchedIntoTheSubnetOutOfBandFailsTheDeleteUntilItIsTerminated() {
        String stack = "cfn-vpc-resident-" + Long.toString(System.nanoTime(), 36);
        cloudFormation(stack, "CreateStack", template("48", "resident"));
        Map<String, String> created = outputs(stack, "CREATE_COMPLETE");
        String subnetId = created.get("SubnetId");
        String instanceId = XmlPath.from(ec2("RunInstances", Map.of(
                        "ImageId", "ami-1234567890abcdef0", "InstanceType", "t3.micro",
                        "MinCount", "1", "MaxCount", "1", "SubnetId", subnetId)))
                .getString("RunInstancesResponse.instancesSet.item.instanceId");

        cloudFormation(stack, "DeleteStack", null);

        CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals("DELETE_FAILED", failed.status(), failed.reason());
        // The event carries the EC2 DependencyViolation message; the engine records the message, not the code.
        String subnetFailure = XmlPath.from(cloudFormationQuery(stack, "DescribeStackEvents"))
                .getString("DescribeStackEventsResponse.DescribeStackEventsResult.StackEvents.member"
                        + ".find { it.LogicalResourceId == 'Subnet' && it.ResourceStatus == 'DELETE_FAILED' }"
                        + ".ResourceStatusReason");
        assertEquals("The subnet '" + subnetId + "' has dependencies and cannot be deleted.", subnetFailure,
                "the subnet's delete must report the resident instance");
        assertTrue(ec2("DescribeSubnets").contains(subnetId), "the pinned subnet must still exist");
        assertTrue(ec2("DescribeVpcs").contains(created.get("VpcId")), "the VPC behind it must still exist");

        ec2("TerminateInstances", Map.of("InstanceId.1", instanceId));
        cloudFormation(stack, "DeleteStack", null);

        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
        assertFalse(ec2("DescribeSubnets").contains(subnetId), "stack delete must remove the subnet");
        assertFalse(ec2("DescribeVpcs").contains(created.get("VpcId")), "stack delete must remove the VPC");
    }

    private static String describeVpc(String vpcId) {
        return ec2("DescribeVpcs", Map.of("VpcId.1", vpcId));
    }

    private static String dnsHostnames(String vpcId) {
        return XmlPath.from(ec2("DescribeVpcAttribute", Map.of("VpcId", vpcId, "Attribute", "enableDnsHostnames")))
                .getString("DescribeVpcAttributeResponse.enableDnsHostnames.value");
    }

    private static Map<String, String> outputs(String stack, String expectedStatus) {
        return XmlParser.extractPairs(describeStacks(stack, expectedStatus), "Outputs", "OutputKey", "OutputValue");
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String cloudFormationQuery(String stack, String action) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .extract().asString();
    }

    private static String ec2(String action) {
        return ec2(action, Map.of());
    }

    private static String ec2(String action, Map<String, String> params) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", action)
            .formParam("Version", "2016-11-15");
        params.forEach(request::formParam);
        return request.when().post("/").then().statusCode(200).extract().asString();
    }
}
