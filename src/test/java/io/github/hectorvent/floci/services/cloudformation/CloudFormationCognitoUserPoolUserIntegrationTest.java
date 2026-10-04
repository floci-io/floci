package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions {@code AWS::Cognito::UserPoolUser} through CloudFormation stacks and reads the users
 * back through the Cognito JSON API: Ref is the template username or a generated one, an update that
 * changes a custom-named user is refused and rolled back with the user untouched, a renamed user is
 * replaced and the old one deleted once the update commits, DeleteStack removes the users, and in an
 * email-as-username pool Ref is the user's sub.
 */
@QuarkusTest
class CloudFormationCognitoUserPoolUserIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String COGNITO = "AWSCognitoIdentityProviderService";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStackProvisionsUsersWhoseRefIsTheirUsername() {
        String stack = "cfn-cognito-users-" + Long.toString(System.nanoTime(), 36);

        stackAction(stack, "CreateStack", poolAndUsersTemplate(), Map.of());
        String outputs = describeStacks(stack, "CREATE_COMPLETE");
        String poolId = outputValue(outputs, "PoolId");
        String generated = outputValue(outputs, "GeneratedRef");
        assertEquals("user-one", outputValue(outputs, "NamedRef"));
        assertThat(generated, matchesPattern(Pattern.quote(stack) + "-GeneratedUser-[a-z0-9]{12}"));
        adminGetUser(poolId, "user-one")
            .statusCode(200)
            .body("Username", equalTo("user-one"))
            .body("UserAttributes.find { it.Name == 'email' }.Value", equalTo("one@example.com"))
            .body("UserAttributes.find { it.Name == 'name' }.Value", equalTo("One"));
        adminGetUser(poolId, generated).statusCode(200).body("Username", equalTo(generated));

        stackAction(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        cognito("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * The pool lives outside the stack, so the update exercises only the users and DeleteStack can
     * show each user is gone rather than only its pool.
     */
    @Test
    void aChangedCustomNamedUserIsRefusedARenameReplacesItAndDeleteStackRemovesIt() {
        String stack = "cfn-cognito-users-update-" + Long.toString(System.nanoTime(), 36);
        String poolId = cognito("CreateUserPool", "{\"PoolName\": \"" + stack + "\"}")
            .statusCode(200)
            .extract().path("UserPool.Id");

        stackAction(stack, "CreateStack", usersInExistingPoolTemplate(), userParameters(poolId, "user-one", "one@example.com"));
        String outputs = describeStacks(stack, "CREATE_COMPLETE");
        String generated = outputValue(outputs, "GeneratedRef");
        assertEquals("user-one", outputValue(outputs, "NamedRef"));
        String sub = adminGetUser(poolId, "user-one")
            .statusCode(200)
            .extract().path("UserAttributes.find { it.Name == 'sub' }.Value");

        // Every property is createOnly and the username is explicit, so CloudFormation refuses it.
        stackAction(stack, "UpdateStack", usersInExistingPoolTemplate(), userParameters(poolId, "user-one", "two@example.com"));
        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertTrue(XmlParser.extractGroups(describeEvents(stack), "member").stream()
                .anyMatch(event -> "NamedUser".equals(event.get("LogicalResourceId"))
                        && "UPDATE_FAILED".equals(event.get("ResourceStatus"))
                        && event.getOrDefault("ResourceStatusReason", "")
                                .contains("custom-named resource requires replacing. Rename user-one")),
                "no refused update event for NamedUser");
        adminGetUser(poolId, "user-one")
            .statusCode(200)
            .body("UserAttributes.find { it.Name == 'email' }.Value", equalTo("one@example.com"))
            .body("UserAttributes.find { it.Name == 'sub' }.Value", equalTo(sub));

        stackAction(stack, "UpdateStack", usersInExistingPoolTemplate(), userParameters(poolId, "user-one-b", "one@example.com"));
        outputs = describeStacks(stack, "UPDATE_COMPLETE");
        assertEquals("user-one-b", outputValue(outputs, "NamedRef"));
        assertEquals(generated, outputValue(outputs, "GeneratedRef"));
        adminGetUser(poolId, "user-one-b")
            .statusCode(200)
            .body("UserAttributes.find { it.Name == 'email' }.Value", equalTo("one@example.com"));
        adminGetUser(poolId, "user-one").statusCode(400).body("__type", equalTo("UserNotFoundException"));
        adminGetUser(poolId, generated).statusCode(200);

        stackAction(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
        adminGetUser(poolId, "user-one-b").statusCode(400).body("__type", equalTo("UserNotFoundException"));
        adminGetUser(poolId, generated).statusCode(400).body("__type", equalTo("UserNotFoundException"));
        cognito("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}").statusCode(200);
    }

    @Test
    void anEmailAsUsernamePoolRefsTheUsersSub() {
        String stack = "cfn-cognito-users-alias-" + Long.toString(System.nanoTime(), 36);

        stackAction(stack, "CreateStack", emailPoolTemplate(), Map.of());
        String outputs = describeStacks(stack, "CREATE_COMPLETE");
        String ref = outputValue(outputs, "UserRef");
        assertThat(ref, matchesPattern(UUID_PATTERN));
        adminGetUser(outputValue(outputs, "PoolId"), "alias.user@example.com")
            .statusCode(200)
            .body("Username", equalTo(ref))
            .body("UserAttributes.find { it.Name == 'sub' }.Value", equalTo(ref))
            .body("UserAttributes.find { it.Name == 'email' }.Value", equalTo("alias.user@example.com"));

        stackAction(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static void stackAction(String stack, String action, String templateBody, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String describeEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse adminGetUser(String poolId, String username) {
        return cognito("AdminGetUser", "{\"UserPoolId\": \"" + poolId + "\", \"Username\": \"" + username + "\"}");
    }

    private static ValidatableResponse cognito(String action, String body) {
        return RestAssuredJsonUtils.awsAction(COGNITO, action, body).then();
    }

    private static Map<String, String> userParameters(String poolId, String username, String email) {
        return Map.of("PoolId", poolId, "Username", username, "Email", email);
    }

    private static String poolAndUsersTemplate() {
        return """
                {
                  "Resources": {
                    "Pool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": { "UserPoolName": "cfn-user-pool" }
                    },
                    "NamedUser": {
                      "Type": "AWS::Cognito::UserPoolUser",
                      "Properties": {
                        "UserPoolId": { "Ref": "Pool" },
                        "Username": "user-one",
                        "MessageAction": "SUPPRESS",
                        "UserAttributes": [
                          { "Name": "email", "Value": "one@example.com" },
                          { "Name": "name", "Value": "One" }
                        ]
                      }
                    },
                    "GeneratedUser": {
                      "Type": "AWS::Cognito::UserPoolUser",
                      "Properties": { "UserPoolId": { "Ref": "Pool" }, "MessageAction": "SUPPRESS" }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "Pool" } },
                    "NamedRef": { "Value": { "Ref": "NamedUser" } },
                    "GeneratedRef": { "Value": { "Ref": "GeneratedUser" } }
                  }
                }
                """;
    }

    private static String usersInExistingPoolTemplate() {
        return """
                {
                  "Parameters": {
                    "PoolId": { "Type": "String" },
                    "Username": { "Type": "String" },
                    "Email": { "Type": "String" }
                  },
                  "Resources": {
                    "NamedUser": {
                      "Type": "AWS::Cognito::UserPoolUser",
                      "Properties": {
                        "UserPoolId": { "Ref": "PoolId" },
                        "Username": { "Ref": "Username" },
                        "MessageAction": "SUPPRESS",
                        "UserAttributes": [ { "Name": "email", "Value": { "Ref": "Email" } } ]
                      }
                    },
                    "GeneratedUser": {
                      "Type": "AWS::Cognito::UserPoolUser",
                      "Properties": { "UserPoolId": { "Ref": "PoolId" } }
                    }
                  },
                  "Outputs": {
                    "NamedRef": { "Value": { "Ref": "NamedUser" } },
                    "GeneratedRef": { "Value": { "Ref": "GeneratedUser" } }
                  }
                }
                """;
    }

    private static String emailPoolTemplate() {
        return """
                {
                  "Resources": {
                    "EmailPool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": { "UserPoolName": "cfn-email-pool", "UsernameAttributes": ["email"] }
                    },
                    "EmailUser": {
                      "Type": "AWS::Cognito::UserPoolUser",
                      "Properties": {
                        "UserPoolId": { "Ref": "EmailPool" },
                        "Username": "alias.user@example.com",
                        "MessageAction": "SUPPRESS",
                        "UserAttributes": [ { "Name": "email", "Value": "alias.user@example.com" } ]
                      }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "EmailPool" } },
                    "UserRef": { "Value": { "Ref": "EmailUser" } }
                  }
                }
                """;
    }
}
