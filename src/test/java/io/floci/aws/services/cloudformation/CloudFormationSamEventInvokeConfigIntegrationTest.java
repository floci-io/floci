package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.testing.CfnRequests.physicalIdUnauthenticated;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SAM function's {@code EventInvokeConfig}, deployed and read back through the Lambda and IAM
 * APIs. Before this the property was dropped, so a function declaring retries and a failure
 * destination deployed with Lambda's defaults and no destination at all.
 */
@QuarkusTest
class CloudFormationSamEventInvokeConfigIntegrationTest {

    /**
     * A conditional function whose failure destination is itself conditional, which makes SAM's
     * translator join the two conditions in a shape CloudFormation rejects.
     */
    private static final String CONDITIONAL_FUNCTION_AND_DESTINATION = """
        {
          "Transform": "AWS::Serverless-2016-10-31",
          "Conditions": {
            "Deploy": { "Fn::Equals": ["a", "a"] },
            "UseOwn": { "Fn::Equals": ["a", "b"] }
          },
          "Resources": {
            "Fn": {
              "Type": "AWS::Serverless::Function",
              "Condition": "Deploy",
              "Properties": {
                "Runtime": "python3.12",
                "Handler": "index.handler",
                "InlineCode": "def handler(e, c): return 'ok'",
                "EventInvokeConfig": { "DestinationConfig": { "OnFailure": { "Type": "SQS",
                  "Destination": { "Fn::If": ["UseOwn", "arn:aws:sqs:us-east-1:000000000000:q", { "Ref": "AWS::NoValue" }] } } } }
              }
            }
          }
        }
        """;

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

    /**
     * The shape kbdb's moderation function uses: two retries, six hours, and an SQS queue in the
     * same template as the failure destination. The generated role may send to that queue.
     */
    @Test
    void retriesAgeAndAFailureQueueReachTheFunction() {
        String name = "sam-eic-" + Long.toString(System.nanoTime(), 36);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Dlq": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "%1$s-dlq" } },
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "FunctionName": "%1$s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "EventInvokeConfig": {
                      "MaximumRetryAttempts": 2,
                      "MaximumEventAgeInSeconds": 21600,
                      "DestinationConfig": { "OnFailure": { "Type": "SQS", "Destination": { "Fn::GetAtt": ["Dlq", "Arn"] } } }
                    }
                  }
                }
              }
            }
            """.formatted(name));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        String queueArn = "arn:aws:sqs:us-east-1:000000000000:" + name + "-dlq";
        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(200)
            .body("MaximumRetryAttempts", equalTo(2))
            .body("MaximumEventAgeInSeconds", equalTo(21600))
            .body("DestinationConfig.OnFailure.Destination", equalTo(queueArn));

        getRolePolicy(physicalIdUnauthenticated(name, "FnRole"), "FnEventInvokeConfigOnFailureSQSPolicy")
            .body(containsString("sqs:SendMessage"))
            .body(containsString(queueArn))
            .body(not(containsString("Fn::GetAtt")));
    }

    /** With AutoPublishAlias the configuration is on the alias, not on $LATEST. */
    @Test
    void anAutoPublishedFunctionGetsItsConfigurationOnTheAlias() {
        String name = "sam-eic-alias-" + Long.toString(System.nanoTime(), 36);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "FunctionName": "%1$s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "AutoPublishAlias": "live",
                    "EventInvokeConfig": { "MaximumRetryAttempts": 0 }
                  }
                }
              }
            }
            """.formatted(name));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        given().queryParam("Qualifier", "live")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(200)
            .body("MaximumRetryAttempts", equalTo(0));
        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(404);
    }

    /** An SNS destination given only its Type gets the topic SAM creates for it. */
    @Test
    void aDestinationGivenOnlyItsTypeGetsTheTopicSamCreates() {
        String name = "sam-eic-topic-" + Long.toString(System.nanoTime(), 36);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "FunctionName": "%1$s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "EventInvokeConfig": { "DestinationConfig": { "OnSuccess": { "Type": "SNS" } } }
                  }
                }
              }
            }
            """.formatted(name));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        String topicArn = physicalIdUnauthenticated(name, "FnEventInvokeConfigOnSuccessTopic");
        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(200)
            .body("DestinationConfig.OnSuccess.Destination", equalTo(topicArn));
        getRolePolicy(physicalIdUnauthenticated(name, "FnRole"), "FnEventInvokeConfigOnSuccessSNSPolicy")
            .body(containsString("sns:publish"))
            .body(containsString(topicArn));
    }

    /** SAM rejects a destination Type it does not know, with its own message. */
    @Test
    void anUnknownDestinationTypeFailsTheStackWithSamsMessage() {
        String name = "sam-eic-badtype-" + Long.toString(System.nanoTime(), 36);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "EventInvokeConfig": { "DestinationConfig": { "OnFailure": {
                      "Type": "sqs", "Destination": "arn:aws:sqs:us-east-1:000000000000:q" } } }
                  }
                }
              }
            }
            """);
        CfnStackWaits.StackState state = CfnStackWaits.awaitStatus(name, "CREATE_FAILED");

        assertEquals("Resource with id [Fn] is invalid. 'Type: sqs' must be one of "
                + "['SQS', 'SNS', 'EventBridge', 'Lambda', 'S3Bucket']", state.reason());
    }

    /**
     * A conditional function whose destination is itself conditional: SAM's translator joins the
     * two conditions with an Fn::And whose operands are {"Condition": {"Condition": name}}, and
     * CloudFormation rejects the template it submits. Measured on CloudFormation (us-east-2), the
     * stack fails during creation, after the transform, with this reason.
     */
    @Test
    void aConditionalFunctionWithAConditionalDestinationFailsAsOnAws() {
        String name = "sam-eic-and-" + Long.toString(System.nanoTime(), 36);
        createStack(name, CONDITIONAL_FUNCTION_AND_DESTINATION);
        CfnStackWaits.StackState state = CfnStackWaits.awaitStatus(name, "CREATE_FAILED");

        assertEquals("Template error: every Condition object must have a single String value.", state.reason());
    }

    /**
     * The same template through a change set, as {@code sam deploy} submits it. Measured on
     * CloudFormation (us-east-2): the change set is FAILED, not executable, with the template error
     * as its reason, and DescribeChangeSet answers normally rather than with an error.
     */
    @Test
    void theSameTemplateFailsItsChangeSetWithTheTemplateError() {
        String name = "sam-eic-cs-" + Long.toString(System.nanoTime(), 36);
        stacksToDelete.add(name);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", name)
            .formParam("ChangeSetName", "deploy")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", CONDITIONAL_FUNCTION_AND_DESTINATION)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        XmlPath changeSet = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", name)
            .formParam("ChangeSetName", "deploy")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        assertEquals("FAILED", changeSet.getString("**.find { it.name() == 'Status' }"));
        assertEquals("UNAVAILABLE", changeSet.getString("**.find { it.name() == 'ExecutionStatus' }"));
        assertEquals("Template error: every Condition object must have a single String value.",
                changeSet.getString("**.find { it.name() == 'StatusReason' }"));
    }

    /** A Type given as a parameter reference takes the value the stack is given over the Default. */
    @Test
    void aParameterTypeTakesTheValueTheStackIsGiven() {
        String name = "sam-eic-param-" + Long.toString(System.nanoTime(), 36);
        stacksToDelete.add(name);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", name)
            .formParam("TemplateBody", """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Parameters": { "DestType": { "Type": "String", "Default": "SQS" } },
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Serverless::Function",
                      "Properties": {
                        "FunctionName": "%1$s",
                        "Runtime": "python3.12",
                        "Handler": "index.handler",
                        "InlineCode": "def handler(e, c): return 'ok'",
                        "EventInvokeConfig": { "DestinationConfig": { "OnFailure": { "Type": { "Ref": "DestType" } } } }
                      }
                    }
                  }
                }
                """.formatted(name))
            .formParam("Parameters.member.1.ParameterKey", "DestType")
            .formParam("Parameters.member.1.ParameterValue", "SNS")
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        String topicArn = physicalIdUnauthenticated(name, "FnEventInvokeConfigOnFailureTopic");
        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(200)
            .body("DestinationConfig.OnFailure.Destination", equalTo(topicArn));
    }

    /**
     * CloudFormation resolves an AWS::SSM::Parameter::Value parameter before it runs the
     * transform, so SAM reads the stored value. Measured on CloudFormation (us-east-2): a Type
     * taken from such a parameter holding SNS creates the topic and makes it the destination.
     */
    @Test
    void aTypeFromAnSsmParameterUsesTheStoredValue() {
        String name = "sam-eic-ssm-" + Long.toString(System.nanoTime(), 36);
        String parameterName = "/" + name + "/dest-type";
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .body("{\"Name\": \"" + parameterName + "\", \"Type\": \"String\", \"Value\": \"SNS\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Parameters": { "DestType": { "Type": "AWS::SSM::Parameter::Value<String>", "Default": "%2$s" } },
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "FunctionName": "%1$s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "EventInvokeConfig": { "DestinationConfig": { "OnFailure": { "Type": { "Ref": "DestType" } } } }
                  }
                }
              }
            }
            """.formatted(name, parameterName));
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");

        String topicArn = physicalIdUnauthenticated(name, "FnEventInvokeConfigOnFailureTopic");
        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(200)
            .body("DestinationConfig.OnFailure.Destination", equalTo(topicArn));
    }

    /**
     * A Destination of AWS::NoValue passes through SAM unchanged and leaves the destination without
     * an ARN. Measured on CloudFormation (us-east-2): the stack fails validation instead of
     * creating a configuration with an empty destination.
     */
    @Test
    void aNoValueDestinationFailsTheStackInsteadOfStoringAnEmptyOne() {
        String name = "sam-eic-novalue-" + Long.toString(System.nanoTime(), 36);
        createStack(name, """
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e, c): return 'ok'",
                    "EventInvokeConfig": { "DestinationConfig": { "OnFailure": { "Type": "SQS", "Destination": { "Ref": "AWS::NoValue" } } } }
                  }
                }
              }
            }
            """.formatted(name));
        CfnStackWaits.StackState state = CfnStackWaits.awaitStatus(name, "ROLLBACK_COMPLETE");

        given().queryParam("Qualifier", "$LATEST")
        .when().get("/2019-09-25/functions/" + name + "/event-invoke-config")
        .then()
            .statusCode(404);
        assertEquals("ROLLBACK_COMPLETE", state.status());
    }

    private void createStack(String stackName, String template) {
        stacksToDelete.add(stackName);
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
    }

    private static ValidatableResponse getRolePolicy(String roleName, String policyName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyName", policyName)
        .when().post("/").then().statusCode(200);
    }
}
