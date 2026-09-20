package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

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
