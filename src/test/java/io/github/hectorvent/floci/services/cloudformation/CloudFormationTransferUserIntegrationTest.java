package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationTransferUserIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String TRANSFER_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/transfer/aws4_request";
    private static final String STACK = "cfn-transfer-user-integration";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Bucket": {"Type": "AWS::S3::Bucket"},
                "Role": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {
                    "AssumeRolePolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [{"Effect": "Allow",
                        "Principal": {"Service": "transfer.amazonaws.com"}, "Action": "sts:AssumeRole"}]
                    }
                  }
                },
                "Server": {
                  "Type": "AWS::Transfer::Server",
                  "Properties": {"Protocols": ["SFTP"], "IdentityProviderType": "SERVICE_MANAGED",
                    "EndpointType": "PUBLIC"}
                },
                "User": {
                  "Type": "AWS::Transfer::User",
                  "Properties": {
                    "ServerId": {"Fn::GetAtt": ["Server", "ServerId"]},
                    "UserName": "%s",
                    "Role": {"Fn::GetAtt": ["Role", "Arn"]},
                    "HomeDirectory": {"Fn::Sub": "/${Bucket}/%s"},
                    "SshPublicKeys": ["%s"]
                  }
                }
              },
              "Outputs": {
                "UserRef": {"Value": {"Ref": "User"}},
                "UserArn": {"Value": {"Fn::GetAtt": ["User", "Arn"]}},
                "ServerId": {"Value": {"Fn::GetAtt": ["Server", "ServerId"]}},
                "RoleArn": {"Value": {"Fn::GetAtt": ["Role", "Arn"]}},
                "BucketName": {"Value": {"Ref": "Bucket"}}
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateReplaceAndDeleteUser() {
        cloudFormation("CreateStack", TEMPLATE.formatted("example-user", "home", "ssh-rsa AAAAfirst"));
        Map<String, String> outputs = outputs("CREATE_COMPLETE");
        String serverId = outputs.get("ServerId");
        String arn = outputs.get("UserArn");
        assertEquals(arn, outputs.get("UserRef"));
        assertEquals("arn:aws:transfer:us-east-1:000000000000:user/" + serverId + "/example-user", arn);
        describeUser(serverId, "example-user")
                .statusCode(200)
                .body("User.Arn", equalTo(arn))
                .body("User.Role", equalTo(outputs.get("RoleArn")))
                .body("User.HomeDirectory", equalTo("/" + outputs.get("BucketName") + "/home"))
                .body("User.SshPublicKeys.SshPublicKeyBody", hasItem("ssh-rsa AAAAfirst"))
                .body("User.SshPublicKeys[0].DateImported", instanceOf(Number.class));

        cloudFormation("UpdateStack", TEMPLATE.formatted("example-user", "updated", "ssh-rsa AAAAsecond"));
        Map<String, String> updated = outputs("UPDATE_COMPLETE");
        assertEquals(arn, updated.get("UserRef"));
        describeUser(serverId, "example-user")
                .statusCode(200)
                .body("User.HomeDirectory", equalTo("/" + outputs.get("BucketName") + "/updated"))
                .body("User.SshPublicKeys.SshPublicKeyBody", hasItem("ssh-rsa AAAAsecond"))
                .body("User.SshPublicKeys.size()", equalTo(1));

        cloudFormation("UpdateStack", TEMPLATE.formatted("renamed-user", "updated", "ssh-rsa AAAAsecond"));
        Map<String, String> replaced = outputs("UPDATE_COMPLETE");
        assertNotEquals(arn, replaced.get("UserRef"));
        assertTrue(replaced.get("UserArn").endsWith("/" + serverId + "/renamed-user"));
        describeUser(serverId, "renamed-user").statusCode(200);
        describeUser(serverId, "example-user").statusCode(404);

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        describeUser(serverId, "renamed-user").statusCode(404);
    }

    private static void cloudFormation(String action, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(STACK, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse describeUser(String serverId, String userName) {
        return given()
                .header("Authorization", TRANSFER_AUTH)
                .header("X-Amz-Target", "TransferService.DescribeUser")
                .contentType("application/x-amz-json-1.1")
                .body("{\"ServerId\":\"" + serverId + "\",\"UserName\":\"" + userName + "\"}")
                .when().post("/").then();
    }
}
