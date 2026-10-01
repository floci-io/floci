package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationTransferServerIntegrationTest {

    @Inject
    CloudFormationService cloudFormationService;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String TRANSFER_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/transfer/aws4_request";
    private static final String WEST_CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-west-2/cloudformation/aws4_request";
    private static final String WEST_TRANSFER_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-west-2/transfer/aws4_request";
    private static final String STACK = "cfn-transfer-server-integration";
    private static final String ROLLBACK_STACK = "cfn-transfer-server-rollback-integration";
    private static final String WEST_STACK = "cfn-transfer-server-west-integration";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Server": {
                  "Type": "AWS::Transfer::Server",
                  "Properties": {
                    %s
                  }
                },
                "Defaulted": {"Type": "AWS::Transfer::Server"}
              },
              "Outputs": {
                "ServerRef": {"Value": {"Ref": "Server"}},
                "ServerId": {"Value": {"Fn::GetAtt": ["Server", "ServerId"]}},
                "ServerArn": {"Value": {"Fn::GetAtt": ["Server", "Arn"]}},
                "DefaultedRef": {"Value": {"Ref": "Defaulted"}}
              }
            }
            """;
    private static final String ROLLBACK_TEMPLATE = """
            {
              "Resources": {
                "Server": {"Type":"AWS::Transfer::Server", "Properties":{
                  "LoggingRole":"%s", "Tags":[{"Key":"stage","Value":"%s"}]
                }}%s
              },
              "Outputs": {"ServerRef":{"Value":{"Ref":"Server"}}}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteBackedServers() {
        cloudFormation("CreateStack", template("role-1", "TransferSecurityPolicy-2023-05", "first"));
        Map<String, String> outputs = outputs("CREATE_COMPLETE");
        String serverId = outputs.get("ServerRef");
        String defaultedId = outputs.get("DefaultedRef");
        assertEquals(serverId, outputs.get("ServerId"));
        assertTrue(outputs.get("ServerArn").endsWith("server/" + serverId));
        describeServer(serverId)
                .body("Server.ServerId", equalTo(serverId))
                .body("Server.Arn", equalTo(outputs.get("ServerArn")))
                .body("Server.LoggingRole", equalTo("role-1"))
                .body("Server.Tags[0].Key", equalTo("stage"))
                .body("Server.Tags[0].Value", equalTo("first"));
        describeServer(defaultedId)
                .body("Server.Domain", equalTo("S3"))
                .body("Server.EndpointType", equalTo("PUBLIC"))
                .body("Server.IdentityProviderType", equalTo("SERVICE_MANAGED"))
                .body("Server.Protocols", hasItem("SFTP"));
        tagServer(outputs.get("ServerArn"), "external", "keep");

        cloudFormation("UpdateStack", template("role-2", "TransferSecurityPolicy-2020-06", "second"));
        assertEquals(serverId, outputs("UPDATE_COMPLETE").get("ServerRef"));
        StackResource updated = cloudFormationService.describeStackResources(STACK, "us-east-1").stream()
                .filter(resource -> "Server".equals(resource.getLogicalId()))
                .findFirst().orElseThrow();
        assertNull(updated.getAttributes().get("__FlociTransferServerUpdateSnapshot"));
        describeServer(serverId)
                .body("Server.LoggingRole", equalTo("role-2"))
                .body("Server.SecurityPolicyName", equalTo("TransferSecurityPolicy-2020-06"))
                .body("Server.Tags.find { it.Key == 'stage' }.Value", equalTo("second"))
                .body("Server.Tags.find { it.Key == 'external' }.Value", equalTo("keep"));

        cloudFormation("UpdateStack", TEMPLATE.formatted("\"Protocols\": [\"SFTP\"]"));
        assertEquals(serverId, outputs("UPDATE_COMPLETE").get("ServerRef"));
        describeServer(serverId)
                .body("Server.LoggingRole", nullValue())
                .body("Server.Tags.find { it.Key == 'stage' }", nullValue())
                .body("Server.Tags.find { it.Key == 'external' }.Value", equalTo("keep"))
                .body("Server.SecurityPolicyName", equalTo("TransferSecurityPolicy-2020-06"));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        describeServer(serverId).statusCode(404);
        describeServer(defaultedId).statusCode(404);
    }

    @Test
    void laterResourceFailureRestoresServerSettings() {
        cloudFormation(ROLLBACK_STACK, "CreateStack",
                ROLLBACK_TEMPLATE.formatted("original-role", "original", ""));
        String serverId = outputs(ROLLBACK_STACK, "CREATE_COMPLETE").get("ServerRef");
        describeServer(serverId).body("Server.Tags.find { it.Key == 'stage' }.Value", equalTo("original"));
        String failingResource = """
                , "BadServer": {
                    "Type":"AWS::Transfer::Server", "DependsOn":"Server",
                    "Properties":{"WorkflowDetails":{}}
                  }
                """;

        cloudFormation(ROLLBACK_STACK, "UpdateStack",
                ROLLBACK_TEMPLATE.formatted("changed-role", "changed", failingResource));
        assertEquals(serverId, outputs(ROLLBACK_STACK, "UPDATE_ROLLBACK_COMPLETE").get("ServerRef"));
        describeServer(serverId)
                .body("Server.LoggingRole", equalTo("original-role"))
                .body("Server.Tags.find { it.Key == 'stage' }.Value", equalTo("original"));

        cloudFormation(ROLLBACK_STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(ROLLBACK_STACK);
        describeServer(serverId).statusCode(404);
    }

    @Test
    void serverFollowsNonDefaultStackRegionThroughLifecycle() {
        cloudFormation(WEST_STACK, "CreateStack", TEMPLATE.formatted("\"LoggingRole\":\"west-original\""),
                WEST_CFN_AUTH);
        Map<String, String> created = outputs(WEST_STACK, "CREATE_COMPLETE", WEST_CFN_AUTH);
        String serverId = created.get("ServerRef");
        assertTrue(created.get("ServerArn").contains(":us-west-2:"));
        describeServer(serverId, WEST_TRANSFER_AUTH)
                .statusCode(200)
                .body("Server.LoggingRole", equalTo("west-original"));
        describeServer(serverId).statusCode(404);

        cloudFormation(WEST_STACK, "UpdateStack", TEMPLATE.formatted("\"LoggingRole\":\"west-updated\""),
                WEST_CFN_AUTH);
        assertEquals(serverId, outputs(WEST_STACK, "UPDATE_COMPLETE", WEST_CFN_AUTH).get("ServerRef"));
        describeServer(serverId, WEST_TRANSFER_AUTH)
                .statusCode(200)
                .body("Server.LoggingRole", equalTo("west-updated"));
        describeServer(serverId).statusCode(404);

        cloudFormation(WEST_STACK, "DeleteStack", null, WEST_CFN_AUTH);
        CfnStackWaits.awaitStackDeleted(WEST_STACK, WEST_CFN_AUTH);
        describeServer(serverId, WEST_TRANSFER_AUTH).statusCode(404);
    }

    private static String template(String role, String policy, String stage) {
        return TEMPLATE.formatted("\"Protocols\": [\"SFTP\"], "
                + "\"LoggingRole\": \"" + role + "\", "
                + "\"SecurityPolicyName\": \"" + policy + "\", "
                + "\"Tags\": [{\"Key\":\"stage\",\"Value\":\"" + stage + "\"}]");
    }

    private static void cloudFormation(String action, String template) {
        cloudFormation(STACK, action, template);
    }

    private static void cloudFormation(String stack, String action, String template) {
        cloudFormation(stack, action, template, CFN_AUTH);
    }

    private static void cloudFormation(String stack, String action, String template, String authorization) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action)
                .formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String expectedStatus) {
        return outputs(STACK, expectedStatus);
    }

    private static Map<String, String> outputs(String stack, String expectedStatus) {
        return outputs(stack, expectedStatus, CFN_AUTH);
    }

    private static Map<String, String> outputs(String stack, String expectedStatus, String authorization) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, authorization);
        assertEquals(expectedStatus, state.status(), state.reason());
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse describeServer(String serverId) {
        return describeServer(serverId, TRANSFER_AUTH);
    }

    private static ValidatableResponse describeServer(String serverId, String authorization) {
        return given()
                .header("Authorization", authorization)
                .header("X-Amz-Target", "TransferService.DescribeServer")
                .contentType("application/x-amz-json-1.1")
                .body("{\"ServerId\":\"" + serverId + "\"}")
                .when().post("/").then();
    }

    private static void tagServer(String arn, String key, String value) {
        given()
                .header("Authorization", TRANSFER_AUTH)
                .header("X-Amz-Target", "TransferService.TagResource")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Arn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"" + key
                        + "\",\"Value\":\"" + value + "\"}]}")
                .when().post("/").then().statusCode(200);
    }
}
