package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code AWS::Lambda::Function.DurableConfig} through CloudFormation: created, updated in place,
 * and removed by replacing the function, with the defaults CloudFormation applies on AWS.
 */
@QuarkusTest
class CloudFormationLambdaDurableConfigIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LAMBDA = "/2015-03-31/functions/";

    @Test
    void durableConfigIsCreatedUpdatedInPlaceAndRemovedByReplacement() {
        String stackName = "cfn-lambda-durable-" + Long.toString(System.nanoTime(), 36);

        deploy("CreateStack", stackName, template("""
                "DurableConfig": {"ExecutionTimeout": {"Ref": "ExecutionTimeout"}, "RetentionPeriodInDays": 3}
                """), "60");
        String created = functionName(stackName);
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("Timeout", equalTo(60))
            .body("DurableConfig.ExecutionTimeout", equalTo(60))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(3))
            .body("LoggingConfig.LogFormat", equalTo("JSON"));
        given()
        .when()
            .get(LAMBDA + created + "/configuration?Qualifier=1")
        .then()
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(60));

        deploy("UpdateStack", stackName, template("""
                "DurableConfig": {"ExecutionTimeout": {"Ref": "ExecutionTimeout"}}
                """), "120");
        assertEquals(created, functionName(stackName), "a changed DurableConfig updates the function in place");
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("Timeout", equalTo(3))
            .body("DurableConfig.ExecutionTimeout", equalTo(120))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(14));

        deploy("UpdateStack", stackName, template("\"Description\": \"plain\""), "120");
        String replaced = functionName(stackName);
        assertNotEquals(created, replaced, "Lambda cannot remove a DurableConfig, so the function is replaced");
        given()
        .when()
            .get(LAMBDA + replaced + "/configuration")
        .then()
            .statusCode(200)
            .body("DurableConfig", nullValue())
            .body("LoggingConfig.LogFormat", equalTo("Text"));
        given()
        .when()
            .get(LAMBDA + created)
        .then()
            .statusCode(404);
    }

    @Test
    void aDurableConfigRemovedWithNoValueLeavesTheFunctionPlain() {
        String stackName = "cfn-lambda-novalue-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {
                  "Parameters": {"ExecutionTimeout": {"Type": "Number"}},
                  "Conditions": {"Durable": {"Fn::Equals": ["yes", "no"]}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "DurableConfig": {"Fn::If": ["Durable", {"ExecutionTimeout": {"Ref": "ExecutionTimeout"}},
                                                     {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """;

        deploy("CreateStack", stackName, template, "60");

        given()
        .when()
            .get(LAMBDA + functionName(stackName) + "/configuration")
        .then()
            .statusCode(200)
            .body("DurableConfig", nullValue());
    }

    private static String template(String functionProperty) {
        return """
                {
                  "Parameters": {"ExecutionTimeout": {"Type": "Number"}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        %s
                      }
                    },
                    "Ver": {"Type": "AWS::Lambda::Version", "Properties": {"FunctionName": {"Ref": "Fn"}}}
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(functionProperty);
    }

    private static void deploy(String action, String stackName, String template, String executionTimeout) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "ExecutionTimeout")
            .formParam("Parameters.member.1.ParameterValue", executionTimeout)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CreateStack".equals(action) ? "CREATE_COMPLETE" : "UPDATE_COMPLETE", state.status(),
                state.reason());
    }

    private static String functionName(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'OutputValue' }");
    }
}
