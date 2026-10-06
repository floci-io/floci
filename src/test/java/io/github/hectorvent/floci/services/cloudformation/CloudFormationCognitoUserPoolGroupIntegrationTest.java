package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class CloudFormationCognitoUserPoolGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser(COGNITO_CONTENT_TYPE, Parser.JSON);
    }

    @Test
    void createStackProvisionsGroupsVisibleToCognito() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-groups-" + suffix;

        String poolId = createStack(stackName, poolAndGroupsTemplate("admin", 0));

        // Both declared groups exist, and only those two.
        cognito("ListGroups", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            .body("Groups", hasSize(2))
            .body("Groups.GroupName", containsInAnyOrder("admin", "readers"));

        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"admin\"}")
            .statusCode(200)
            .body("Group.Description", equalTo("Back office operators"))
            .body("Group.Precedence", equalTo(0));

        // The matched negative: a group the template never declared is genuinely absent, through
        // the same call against the same pool. Without it, a lookup that answered "present" for
        // anything would look identical to a working provisioner.
        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"admins\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void groupRefResolvesToTheGroupName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-ref-" + suffix;

        createStack(stackName, poolAndGroupsTemplate("admin", 0));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<OutputKey>AdminGroupName</OutputKey>"))
            .body(containsString("<OutputValue>admin</OutputValue>"));
    }

    @Test
    void updateStackReconcilesExistingGroupInsteadOfFailing() {
        // provision() re-runs for every resource on every UpdateStack whether or not its
        // properties changed, so a group left alone between deploys must reconcile rather than
        // call CreateGroup again and roll the stack back with GroupExistsException.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-update-" + suffix;

        String poolId = createStack(stackName, poolAndGroupsTemplate("admin", 0));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", poolAndGroupsTemplate("admin", 7))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")));

        cognito("ListGroups", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            .body("Groups", hasSize(2));

        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"admin\"}")
            .statusCode(200)
            .body("Group.Precedence", equalTo(7));
    }

    @Test
    void removingAGroupFromTheTemplateDeletesOnlyThatGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-delete-" + suffix;

        String poolId = createStack(stackName, poolAndGroupsTemplate("admin", 0));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", poolAndStudentGroupOnlyTemplate())
        .when()
            .post("/")
        .then()
            .statusCode(200);

        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"admin\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"readers\"}")
            .statusCode(200);
    }

    @Test
    void renamingAGroupReplacesItAndDeletesTheGroupUnderTheOldName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-rename-" + suffix;

        String poolId = createStack(stackName, poolAndGroupsTemplate("admin", 0));

        updateStack(stackName, poolAndGroupsTemplate("operators", 0));

        String describeXml = describeStack(stackName);
        assertThat(describeXml, containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"));
        assertEquals("operators", output(describeXml, "AdminGroupName"));
        cognito("ListGroups", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", containsInAnyOrder("operators", "readers"));
        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"admin\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void movingAnUnnamedGroupToAnotherPoolReplacesItUnderANewGeneratedName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-move-" + suffix;

        String firstPool = createStack(stackName, twoPoolsAndUnnamedGroupTemplate("FirstPool"));
        String describeXml = describeStack(stackName);
        String secondPool = output(describeXml, "SecondPoolId");
        String original = output(describeXml, "GroupName");

        updateStack(stackName, twoPoolsAndUnnamedGroupTemplate("SecondPool"));

        describeXml = describeStack(stackName);
        assertThat(describeXml, containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"));
        String replacement = output(describeXml, "GroupName");
        assertNotEquals(original, replacement, "a pool move replaces the group under a fresh name");
        cognito("ListGroups", "{\"UserPoolId\": \"" + secondPool + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", contains(replacement));
        cognito("ListGroups", "{\"UserPoolId\": \"" + firstPool + "\"}")
            .statusCode(200)
            .body("Groups", hasSize(0));
    }

    @Test
    void aFailedUpdateAfterMovingANamedGroupKeepsTheOldPoolGroupAndLeavesTheNewOneBehind() {
        // Measured on AWS: the group's physical id, the bare name, does not change, so the rollback
        // points the resource at the old pool's group again and skips deleting the new pool's group.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-move-rollback-" + suffix;
        String name = "movers-" + suffix;
        String firstPool = createPool(stackName + "-p1");
        String secondPool = createPool(stackName + "-p2");

        createStackAndWait(stackName, namedGroupTemplate(firstPool, name, false));

        // Duplicate depends on Group and claims the new pool and the same name, so it fails after
        // the group exists in the new pool and the update rolls back.
        updateStack(stackName, namedGroupTemplate(secondPool, name, true));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertEquals(name, output(describeStack(stackName), "GroupRef"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResource")
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "Group")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + name + "</PhysicalResourceId>"));
        cognito("GetGroup", "{\"UserPoolId\": \"" + firstPool + "\", \"GroupName\": \"" + name + "\"}")
            .statusCode(200)
            .body("Group.Description", equalTo("d1"))
            .body("Group.Precedence", equalTo(1));
        cognito("ListGroups", "{\"UserPoolId\": \"" + firstPool + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", contains(name));
        cognito("ListGroups", "{\"UserPoolId\": \"" + secondPool + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", contains(name));

        deleteStackAndWait(stackName);

        cognito("ListGroups", "{\"UserPoolId\": \"" + firstPool + "\"}")
            .statusCode(200)
            .body("Groups", hasSize(0));
        cognito("ListGroups", "{\"UserPoolId\": \"" + secondPool + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", contains(name));
    }

    @Test
    void declaringAGeneratedGroupNameExplicitlyIsRefusedAndRollsBack() {
        // Measured on AWS: GroupName is create-only, so declaring the generated name requires a
        // replacement keeping the same pool and name, which is refused before anything is created.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-group-declare-" + suffix;
        String poolId = createPool(stackName);

        createStackAndWait(stackName, unnamedGroupTemplate(poolId));
        String generated = output(describeStack(stackName), "GroupRef");
        Object createdAt = cognito("GetGroup",
                "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"" + generated + "\"}")
            .statusCode(200)
            .extract().path("Group.CreationDate");

        updateStack(stackName, namedGroupTemplate(poolId, generated, false));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        List<String> failures = XmlParser.extractGroups(describeEvents(stackName), "member").stream()
                .filter(event -> "Group".equals(event.get("LogicalResourceId"))
                        && "UPDATE_FAILED".equals(event.get("ResourceStatus")))
                .map(event -> event.get("ResourceStatusReason"))
                .toList();
        assertThat(failures, contains(containsString("CloudFormation cannot update a stack when a custom-named"
                + " resource requires replacing. Rename " + generated + " and update the stack again.")));
        assertEquals(generated, output(describeStack(stackName), "GroupRef"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResource")
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "Group")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + generated + "</PhysicalResourceId>"));
        cognito("GetGroup", "{\"UserPoolId\": \"" + poolId + "\", \"GroupName\": \"" + generated + "\"}")
            .statusCode(200)
            .body("Group.Description", equalTo("d1"))
            .body("Group.Precedence", equalTo(1))
            .body("Group.CreationDate", equalTo(createdAt));
        cognito("ListGroups", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            .body("Groups.GroupName", contains(generated));

        deleteStackAndWait(stackName);

        cognito("ListGroups", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            .body("Groups", hasSize(0));
    }

    @Test
    void poolSchemaCustomAttributesAreNamespacedAndStandardOnesAreNot() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cognito-schema-" + suffix;

        String poolId = createStack(stackName, poolAndGroupsTemplate("admin", 0));

        cognito("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .statusCode(200)
            // The template declares EmployeeId unprefixed, as CloudFormation and the SDKs do.
            .body("UserPool.SchemaAttributes.find { it.Name == 'custom:EmployeeId' }.AttributeDataType",
                    equalTo("String"))
            .body("UserPool.SchemaAttributes.find { it.Name == 'EmployeeId' }", nullValue())
            // A standard attribute in the same list overrides its default rather than becoming a
            // custom one, so email stays bare and keeps the Required the template asked for.
            .body("UserPool.SchemaAttributes.find { it.Name == 'email' }.Required", equalTo(true))
            .body("UserPool.SchemaAttributes.find { it.Name == 'custom:email' }", nullValue());
    }

    /** Creates the stack, asserts it completed, and returns the pool id from its outputs. */
    private String createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String describeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        return describeXml.split("<OutputKey>PoolId</OutputKey>")[1]
                .split("<OutputValue>")[1].split("</OutputValue>")[0];
    }

    private void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /** Creates the stack and waits for CREATE_COMPLETE, for templates without a PoolId output. */
    private void createStackAndWait(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CREATE_COMPLETE", state.status(), state.reason());
    }

    private void deleteStackAndWait(String stackName) {
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

    private String createPool(String poolName) {
        return cognito("CreateUserPool", "{\"PoolName\": \"" + poolName + "\"}")
            .statusCode(200)
            .extract().path("UserPool.Id");
    }

    private String describeStack(String stackName) {
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

    private String describeEvents(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static String output(String describeXml, String key) {
        return XmlParser.extractPairs(describeXml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private io.restassured.response.ValidatableResponse cognito(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs(COGNITO_CONTENT_TYPE, ContentType.TEXT)))
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + target)
            .contentType(COGNITO_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static String poolAndGroupsTemplate(String adminGroupName, int adminPrecedence) {
        return """
                {
                  "Resources": {
                    "UserPool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": {
                        "UserPoolName": "cfn-group-pool",
                        "Schema": [
                          { "Name": "EmployeeId", "AttributeDataType": "String", "Mutable": true },
                          { "Name": "email", "AttributeDataType": "String", "Required": true }
                        ]
                      }
                    },
                    "AdminGroup": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": { "Ref": "UserPool" },
                        "GroupName": "%s",
                        "Description": "Back office operators",
                        "Precedence": %d
                      }
                    },
                    "StudentGroup": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": { "Ref": "UserPool" },
                        "GroupName": "readers"
                      }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "UserPool" } },
                    "AdminGroupName": { "Value": { "Ref": "AdminGroup" } }
                  }
                }
                """.formatted(adminGroupName, adminPrecedence);
    }

    /** Two pools and a group without a GroupName, placed in the pool named by {@code poolLogicalId}. */
    private static String twoPoolsAndUnnamedGroupTemplate(String poolLogicalId) {
        return """
                {
                  "Resources": {
                    "FirstPool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": { "UserPoolName": "cfn-group-pool-first" }
                    },
                    "SecondPool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": { "UserPoolName": "cfn-group-pool-second" }
                    },
                    "Group": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": { "Ref": "%s" },
                        "Description": "moves between pools"
                      }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "FirstPool" } },
                    "SecondPoolId": { "Value": { "Ref": "SecondPool" } },
                    "GroupName": { "Value": { "Ref": "Group" } }
                  }
                }
                """.formatted(poolLogicalId);
    }

    /**
     * A named group in a pool created outside the stack. With {@code duplicate}, a second group of
     * the same pool and name depends on it and fails with GroupExistsException once it exists.
     */
    private static String namedGroupTemplate(String poolId, String groupName, boolean duplicate) {
        String duplicateResource = !duplicate ? "" : """
                    ,
                    "Duplicate": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "DependsOn": "Group",
                      "Properties": { "UserPoolId": "%s", "GroupName": "%s" }
                    }
                """.formatted(poolId, groupName);
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": "%s",
                        "GroupName": "%s",
                        "Description": "d1",
                        "Precedence": 1
                      }
                    }%s
                  },
                  "Outputs": {
                    "GroupRef": { "Value": { "Ref": "Group" } }
                  }
                }
                """.formatted(poolId, groupName, duplicateResource);
    }

    /** The named group's template without a GroupName, so CloudFormation generates one. */
    private static String unnamedGroupTemplate(String poolId) {
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": "%s",
                        "Description": "d1",
                        "Precedence": 1
                      }
                    }
                  },
                  "Outputs": {
                    "GroupRef": { "Value": { "Ref": "Group" } }
                  }
                }
                """.formatted(poolId);
    }

    private static String poolAndStudentGroupOnlyTemplate() {
        return """
                {
                  "Resources": {
                    "UserPool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": {
                        "UserPoolName": "cfn-group-pool",
                        "Schema": [
                          { "Name": "EmployeeId", "AttributeDataType": "String", "Mutable": true },
                          { "Name": "email", "AttributeDataType": "String", "Required": true }
                        ]
                      }
                    },
                    "StudentGroup": {
                      "Type": "AWS::Cognito::UserPoolGroup",
                      "Properties": {
                        "UserPoolId": { "Ref": "UserPool" },
                        "GroupName": "readers"
                      }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "UserPool" } }
                  }
                }
                """;
    }
}
