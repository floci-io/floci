package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * UpdateStack and CreateChangeSet take "the name or the unique stack ID" as StackName. An UPDATE
 * change set given the stack ID has to land on the stack that ID names, not on a new key spelled
 * like the ARN (floci-io/floci#4842).
 */
@QuarkusTest
class CloudFormationStackIdUpdateIntegrationTest {

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "ParameterName": {"Type": "String"},
                "ParameterValue": {"Type": "String"}
              },
              "Resources": {
                "Param": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": {"Ref": "ParameterName"},
                    "Type": "String",
                    "Value": {"Ref": "ParameterValue"}
                  }
                }
              }
            }
            """;

    private final Set<String> stacksToDelete = new LinkedHashSet<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteStacks() {
        for (String stackName : stacksToDelete) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/");
        }
        stacksToDelete.clear();
    }

    @Test
    void updateStackAcceptsTheStackId() {
        String stackName = "stack-id-update-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName);

        withParameters(stackName, "second")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>" + stackId + "</StackId>"));

        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("UPDATE_COMPLETE"));
        assertThat(parameterValue(stackName), equalTo("second"));
    }

    @Test
    void updateChangeSetAcceptsTheStackId() {
        String stackName = "stack-id-changeset-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName);

        XmlPath created = withParameters(stackName, "second")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackId)
            .formParam("ChangeSetName", "update-by-stack-id")
            .formParam("ChangeSetType", "UPDATE")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        assertThat(created.getString("CreateChangeSetResponse.CreateChangeSetResult.StackId"), equalTo(stackId));
        String changeSetId = created.getString("CreateChangeSetResponse.CreateChangeSetResult.Id");

        XmlPath described = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackId)
            .formParam("ChangeSetName", changeSetId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        assertThat(described.getString("DescribeChangeSetResponse.DescribeChangeSetResult.StackName"),
                equalTo(stackName));
        assertThat(described.getString("DescribeChangeSetResponse.DescribeChangeSetResult.StackId"),
                equalTo(stackId));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", stackId)
            .formParam("ChangeSetName", changeSetId)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("UPDATE_COMPLETE"));
        assertThat(parameterValue(stackName), equalTo("second"));
    }

    @Test
    void updateStackWithTheArnOfAnUnknownStackIsAValidationError() {
        String stackId = "arn:aws:cloudformation:us-east-1:000000000000:stack/stack-id-missing-"
                + Long.toString(System.nanoTime(), 36) + "/00000000-0000-0000-0000-000000000000";

        withParameters("stack-id-missing", "second")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Stack with id " + stackId + " does not exist"));
    }

    @Test
    void aDeletedStackIdDoesNotUpdateANewStackOfTheSameName() {
        String stackName = "stack-id-stale-" + Long.toString(System.nanoTime(), 36);
        String staleStackId = createStack(stackName);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
        String refusal = "Stack:" + staleStackId + " is in DELETE_COMPLETE state and can not be updated.";
        withParameters(stackName, "second")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", staleStackId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(refusal));
        String currentStackId = createStack(stackName);

        withParameters(stackName, "second")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", staleStackId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(refusal));
        withParameters(stackName, "second")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", staleStackId)
            .formParam("ChangeSetName", "update-by-stale-stack-id")
            .formParam("ChangeSetType", "UPDATE")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(refusal));

        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("CREATE_COMPLETE"));
        assertThat(stackId(stackName), equalTo(currentStackId));
        assertThat(parameterValue(stackName), equalTo("first"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListChangeSets")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("update-by-stale-stack-id")));
    }

    private String createStack(String stackName) {
        stacksToDelete.add(stackName);
        String stackId = withParameters(stackName, "first")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getString("CreateStackResponse.CreateStackResult.StackId");
        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("CREATE_COMPLETE"));
        assertThat(parameterValue(stackName), equalTo("first"));
        return stackId;
    }

    private static RequestSpecification withParameters(String stackName, String value) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("TemplateBody", TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "ParameterName")
            .formParam("Parameters.member.1.ParameterValue", "/" + stackName)
            .formParam("Parameters.member.2.ParameterKey", "ParameterValue")
            .formParam("Parameters.member.2.ParameterValue", value);
    }

    private static String stackId(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackId");
    }

    private static String parameterValue(String stackName) {
        return given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType("application/x-amz-json-1.1")
            .body("{\"Name\":\"/" + stackName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Parameter.Value");
    }
}
