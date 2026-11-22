package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.cloudfront.model.ConnectionGroup;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS::CloudFront::ConnectionGroup through the stack lifecycle: the exact {@code Ref} and
 * {@code Fn::GetAtt} values from the registry schema's read-only members, an in-place update, and
 * DeleteStack.
 */
@QuarkusTest
class CloudFormationCloudFrontConnectionGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void connectionGroupFollowsTheStackLifecycle() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cloudfront-connection-group-" + suffix;

        stackCall("CreateStack", stackName, template(suffix, true));
        assertStatus(stackName, "CREATE_COMPLETE");
        String describe = describeStacks(stackName);

        String groupId = output(describe, "GroupRef");
        assertEquals(groupId, output(describe, "GroupId"));
        ConnectionGroup group = cloudFrontService.getConnectionGroup(groupId);
        assertEquals("cfn-connection-group-" + suffix, group.getName());
        assertTrue(group.getEnabled());
        assertTrue(group.getIpv6Enabled());
        assertTrue(group.getArn().endsWith(":connection-group/" + groupId));
        assertEquals(group.getArn(), output(describe, "GroupArn"));
        assertEquals(group.getRoutingEndpoint(), output(describe, "GroupRoutingEndpoint"));
        assertEquals(group.getStatus(), output(describe, "GroupStatus"));
        assertEquals(String.valueOf(group.getIsDefault()), output(describe, "GroupIsDefault"));
        assertEquals(group.getEtag(), output(describe, "GroupEtag"));
        assertEquals(group.getCreatedTime().toString(), output(describe, "GroupCreatedTime"));
        assertEquals(group.getLastModifiedTime().toString(), output(describe, "GroupLastModifiedTime"));

        stackCall("UpdateStack", stackName, template(suffix, false));
        assertStatus(stackName, "UPDATE_COMPLETE");
        ConnectionGroup updated = cloudFrontService.getConnectionGroup(groupId);
        assertEquals(groupId, output(describeStacks(stackName), "GroupRef"), "an update keeps the group");
        assertFalse(updated.getEnabled());
        assertTrue(updated.getIpv6Enabled());

        deleteStack(stackName);
        assertEquals("EntityNotFound",
                assertThrows(AwsException.class, () -> cloudFrontService.getConnectionGroup(groupId)).getErrorCode());
    }

    private static String template(String suffix, boolean enabled) {
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::CloudFront::ConnectionGroup",
                      "Properties": {
                        "Name": "cfn-connection-group-%1$s",
                        "Enabled": %2$s,
                        "Ipv6Enabled": true
                      }
                    }
                  },
                  "Outputs": {
                    "GroupRef": {"Value": {"Ref": "Group"}},
                    "GroupId": {"Value": {"Fn::GetAtt": "Group.Id"}},
                    "GroupArn": {"Value": {"Fn::GetAtt": "Group.Arn"}},
                    "GroupRoutingEndpoint": {"Value": {"Fn::GetAtt": "Group.RoutingEndpoint"}},
                    "GroupStatus": {"Value": {"Fn::GetAtt": "Group.Status"}},
                    "GroupIsDefault": {"Value": {"Fn::GetAtt": "Group.IsDefault"}},
                    "GroupEtag": {"Value": {"Fn::GetAtt": "Group.ETag"}},
                    "GroupCreatedTime": {"Value": {"Fn::GetAtt": "Group.CreatedTime"}},
                    "GroupLastModifiedTime": {"Value": {"Fn::GetAtt": "Group.LastModifiedTime"}}
                  }
                }
                """.formatted(suffix, enabled);
    }

    private static void assertStatus(String stackName, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals(expected, state.status(), state.reason());
    }

    private static void stackCall(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static String output(String describeStacks, String key) {
        String value = XmlParser.extractPairs(describeStacks, "Outputs", "OutputKey", "OutputValue").get(key);
        if (value == null) {
            throw new AssertionError("missing output " + key + " in " + describeStacks);
        }
        return value;
    }
}
