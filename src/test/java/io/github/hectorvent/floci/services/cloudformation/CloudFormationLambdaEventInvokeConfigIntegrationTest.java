package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::Lambda::EventInvokeConfig} through a CloudFormation stack and reads
 * it back through the Lambda API. {@code Ref} is the composite {@code FunctionName|Qualifier} id
 * a real stack shows; a settings change updates the configuration in place; a qualifier change
 * replaces it, deleting the displaced configuration once the update commits; a failed update rolls
 * the replacement back; and the stack delete removes the configuration.
 */
@QuarkusTest
class CloudFormationLambdaEventInvokeConfigIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/cloudformation/aws4_request";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:async-dlq";

    /** The function and its first version, in a stack of their own so the configuration stacks hold nothing else. */
    private static final String FUNCTION_TEMPLATE = """
        {
          "Resources": {
            "Role": {
              "Type": "AWS::IAM::Role",
              "Properties": {
                "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                  {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
              }
            },
            "Fn": {
              "Type": "AWS::Lambda::Function",
              "Properties": {
                "FunctionName": "%s",
                "Runtime": "python3.12",
                "Handler": "index.handler",
                "Role": {"Fn::GetAtt": ["Role", "Arn"]},
                "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
              }
            },
            "Ver": {
              "Type": "AWS::Lambda::Version",
              "Properties": {"FunctionName": {"Ref": "Fn"}}
            }
          },
          "Outputs": {
            "Version": {"Value": {"Fn::GetAtt": ["Ver", "Version"]}}
          }
        }
        """;

    private static final String CONFIG_TEMPLATE = """
        {
          "Parameters": {
            "Qualifier": {"Type": "String", "Default": "$LATEST"},
            "Retries": {"Type": "String", "Default": "1"}
          },
          "Resources": {
            "AsyncConfig": {
              "Type": "AWS::Lambda::EventInvokeConfig",
              "Properties": {
                "FunctionName": "%s",
                "Qualifier": {"Ref": "Qualifier"},
                "MaximumRetryAttempts": {"Ref": "Retries"},
                "MaximumEventAgeInSeconds": 300,
                "DestinationConfig": {"OnFailure": {"Destination": "%s"}}
              }
            }%s
          },
          "Outputs": {
            "ConfigRef": {"Value": {"Ref": "AsyncConfig"}}
          }
        }
        """;

    /** A resource that fails after the configuration, so the update rolls the replacement back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "AsyncConfig",
              "Properties": {
                "Name": "event-invoke-rollback-%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void configurationFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionStack = "cfn-event-invoke-fn-" + suffix;
        String stack = "cfn-event-invoke-" + suffix;
        String fn = "event-invoke-fn-" + suffix;
        String version = createFunctionStack(functionStack, fn);
        String template = CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, "");

        cloudFormation(stack, "CreateStack", template, Map.of());
        assertEquals(fn + "|$LATEST", outputValue(describeStacks(stack, "CREATE_COMPLETE"), "ConfigRef"),
                "Ref is the composite FunctionName|Qualifier identifier");
        getConfig(fn, "$LATEST").then()
            .statusCode(200)
            .body("FunctionArn", containsString(":function:" + fn + ":$LATEST"))
            .body("MaximumRetryAttempts", equalTo(1))
            .body("MaximumEventAgeInSeconds", equalTo(300))
            .body("DestinationConfig.OnFailure.Destination", equalTo(DLQ_ARN))
            .body("DestinationConfig.OnSuccess", nullValue());

        cloudFormation(stack, "UpdateStack", template, Map.of("Retries", "0"));
        assertEquals(fn + "|$LATEST", outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "ConfigRef"),
                "a settings change keeps the same configuration");
        getConfig(fn, "$LATEST").then()
            .statusCode(200)
            .body("MaximumRetryAttempts", equalTo(0))
            .body("MaximumEventAgeInSeconds", equalTo(300))
            .body("DestinationConfig.OnFailure.Destination", equalTo(DLQ_ARN));

        cloudFormation(stack, "UpdateStack", template, Map.of("Retries", "0", "Qualifier", version));
        assertEquals(fn + "|" + version, outputValue(describeStacks(stack, "UPDATE_COMPLETE"), "ConfigRef"),
                "a qualifier change is a replacement");
        getConfig(fn, version).then()
            .statusCode(200)
            .body("FunctionArn", containsString(":function:" + fn + ":" + version))
            .body("MaximumRetryAttempts", equalTo(0));
        getConfig(fn, "$LATEST").then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        assertEvent(describeEvents(stack), "DELETE_COMPLETE", fn + "|$LATEST");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        getConfig(fn, version).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        given().when().get("/2015-03-31/functions/" + fn).then().statusCode(200);

        cloudFormation(functionStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(functionStack);
    }

    @Test
    void aFailedUpdateRollsTheReplacementBack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionStack = "cfn-event-invoke-rb-fn-" + suffix;
        String stack = "cfn-event-invoke-rb-" + suffix;
        String fn = "event-invoke-rb-fn-" + suffix;
        String version = createFunctionStack(functionStack, fn);

        cloudFormation(stack, "CreateStack", CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, ""), Map.of());
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, FAILING_RESOURCE.formatted(suffix)),
                Map.of("Qualifier", version));
        String rolledBack = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(fn + "|$LATEST", outputValue(rolledBack, "ConfigRef"),
                "the resource names the prior configuration again");
        getConfig(fn, "$LATEST").then()
            .statusCode(200)
            .body("MaximumRetryAttempts", equalTo(1));
        getConfig(fn, version).then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(functionStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(functionStack);
    }

    /** An in-place settings change is put back from the snapshot the update took. */
    @Test
    void aFailedUpdateRestoresTheSettingsAnInPlaceUpdateChanged() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionStack = "cfn-event-invoke-ip-fn-" + suffix;
        String stack = "cfn-event-invoke-ip-" + suffix;
        String fn = "event-invoke-ip-fn-" + suffix;
        createFunctionStack(functionStack, fn);

        cloudFormation(stack, "CreateStack", CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, ""), Map.of());
        describeStacks(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, FAILING_RESOURCE.formatted(suffix)),
                Map.of("Retries", "0"));
        String rolledBack = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(fn + "|$LATEST", outputValue(rolledBack, "ConfigRef"));
        getConfig(fn, "$LATEST").then()
            .statusCode(200)
            .body("MaximumRetryAttempts", equalTo(1))
            .body("MaximumEventAgeInSeconds", equalTo(300))
            .body("DestinationConfig.OnFailure.Destination", equalTo(DLQ_ARN));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(functionStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(functionStack);
    }

    @Test
    void anOutOfRangeSettingFailsTheResource() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String functionStack = "cfn-event-invoke-bad-fn-" + suffix;
        String stack = "cfn-event-invoke-bad-" + suffix;
        String fn = "event-invoke-bad-fn-" + suffix;
        createFunctionStack(functionStack, fn);

        cloudFormation(stack, "CreateStack", CONFIG_TEMPLATE.formatted(fn, DLQ_ARN, ""), Map.of("Retries", "3"));

        String failed = awaitStackStatus(stack, "ROLLBACK_COMPLETE");
        assertTrue(failed.contains("MaximumRetryAttempts"),
                "the status reason names the property: " + failed);
        getConfig(fn, "$LATEST").then().statusCode(404);

        cloudFormation(stack, "DeleteStack", null, Map.of());
        awaitStackDeleted(stack);
        cloudFormation(functionStack, "DeleteStack", null, Map.of());
        awaitStackDeleted(functionStack);
    }

    /** Creates the function stack and returns the number of the version it published. */
    private static String createFunctionStack(String stack, String fn) {
        cloudFormation(stack, "CreateStack", FUNCTION_TEMPLATE.formatted(fn), Map.of());
        return outputValue(describeStacks(stack, "CREATE_COMPLETE"), "Version");
    }

    private static Response getConfig(String functionName, String qualifier) {
        return given()
            .queryParam("Qualifier", qualifier)
        .when().get("/2019-09-25/functions/" + functionName + "/event-invoke-config");
    }

    private static void cloudFormation(String stack, String action, String templateBody,
                                       Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM");
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

    private static void assertEvent(String eventsXml, String status, String physicalId) {
        boolean found = XmlParser.extractGroups(eventsXml, "member").stream()
                .anyMatch(event -> status.equals(event.get("ResourceStatus"))
                        && physicalId.equals(event.get("PhysicalResourceId")));
        assertTrue(found, "no " + status + " event for " + physicalId + " in " + eventsXml);
    }

    /** A failed create rolls back asynchronously; returns the events once the status is reached. */
    private static String awaitStackStatus(String stack, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return describeEvents(stack);
            }
            Thread.sleep(50);
        }
        return fail("stack " + stack + " did not reach " + expectedStatus + " within the timeout");
    }

    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
