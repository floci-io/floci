package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * A stack update that changes a Cognito user pool and its client in place and then fails on a later
 * resource rolls both back: the pool and the client carry the settings they had before the update,
 * including a setting the update added being cleared again, and the stack ends in
 * UPDATE_ROLLBACK_COMPLETE rather than UPDATE_ROLLBACK_FAILED.
 */
@QuarkusTest
class CloudFormationCognitoUpdateRollbackIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {
                    "UserPoolName": "%s",
                    "UserPoolTags": {"team": "%s"}%s
                  }
                },
                "Client": {
                  "Type": "AWS::Cognito::UserPoolClient",
                  "Properties": {"UserPoolId": {"Ref": "Pool"}, "ClientName": "%s"}
                }%s
              },
              "Outputs": {
                "PoolId": {"Value": {"Ref": "Pool"}},
                "ClientId": {"Value": {"Ref": "Client"}}
              }
            }
            """;

    private static final String FAILING_RESOURCE = """
            ,
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "Client",
                  "Properties": {
                    "Name": "cognito-rollback-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": {"PasswordLength": 32}
                  }
                }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFailedUpdatePutsThePoolAndTheClientBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-cognito-rb-" + suffix;
        String poolName = "pool-rb-" + suffix;

        cloudFormation(stack, "CreateStack", TEMPLATE.formatted(poolName, "auth", "", "web", ""));
        Map<String, String> outputs = outputs(describeStacks(stack, "CREATE_COMPLETE"));
        String poolId = outputs.get("PoolId");
        String clientId = outputs.get("ClientId");

        String failingUpdate = TEMPLATE.formatted(poolName + "-v2", "data",
                ",\n        \"AutoVerifiedAttributes\": [\"email\"]", "web-v2", FAILING_RESOURCE.formatted(suffix));
        cloudFormation(stack, "UpdateStack", failingUpdate);
        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");

        describeUserPool(poolId).then()
            .statusCode(200)
            .body("UserPool.Name", equalTo(poolName))
            .body("UserPool.UserPoolTags.team", equalTo("auth"))
            .body("UserPool.AutoVerifiedAttributes", empty());
        describeUserPoolClient(poolId, clientId).then()
            .statusCode(200)
            .body("UserPoolClient.ClientName", equalTo("web"));

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        if (templateBody == null) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack)
            .when().post("/").then().statusCode(200);
            return;
        }
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
            .formParam("TemplateBody", templateBody)
        .when().post("/").then().statusCode(200);
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

    private static Map<String, String> outputs(String describeStacksXml) {
        return XmlParser.extractPairs(describeStacksXml, "Outputs", "OutputKey", "OutputValue");
    }

    private static Response describeUserPool(String poolId) {
        return given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\"}")
        .when().post("/");
    }

    private static Response describeUserPoolClient(String poolId, String clientId) {
        return given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPoolClient")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
        .when().post("/");
    }
}
