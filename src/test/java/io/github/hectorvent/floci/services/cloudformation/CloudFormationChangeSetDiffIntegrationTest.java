package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers three ways {@code computeChangeSetChanges} previously misreported a change set's diff:
 * ignoring parameter-only updates, comparing a SAM stack's raw template against its expanded
 * deployed template, and hardcoding {@code Replacement=False} even when a Type change forces a
 * replacement.
 */
@QuarkusTest
class CloudFormationChangeSetDiffIntegrationTest {

    private final List<String> stacksToDelete = new ArrayList<>();

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
    void parameterOnlyUpdate_isReportedAsAChange() {
        String stackName = "cs-diff-param-only-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-${Suffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "one")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Same template, only the parameter value changes.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "param-only-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "two")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "param-only-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Modify</Action>"));
    }

    @Test
    void samStackNoOpUpdate_doesNotReportSpuriousChanges() {
        String stackName = "cs-diff-sam-noop-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HelloFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: cs-diff-sam-func
                  Handler: index.handler
                  Runtime: nodejs22.x
                  InlineCode: |
                    exports.handler = async () => ({ statusCode: 200, body: 'ok' });
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Byte-identical re-submission of the same SAM source: the deployed template is the
        // *expanded* form, so diffing the raw SAM source against it must not report the
        // SAM-generated IAM role as removed or the function as modified.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-noop-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-noop-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Action>Remove</Action>")))
            .body(not(containsString("<Action>Modify</Action>")));
    }

    @Test
    void resourceTypeChange_reportsReplacementTrue() {
        String stackName = "cs-diff-replacement-stack";
        stacksToDelete.add(stackName);

        String initialTemplate = """
            {
              "Resources": {
                "R": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {"TopicName": "cs-diff-repl-topic"}
                }
              }
            }
            """;
        // Same logical id, different resource Type: AWS always forces a replacement here.
        String replacedTemplate = """
            {
              "Resources": {
                "R": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {"QueueName": "cs-diff-repl-queue"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "replacement-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", replacedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "replacement-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Replacement>True</Replacement>"));
    }
}
