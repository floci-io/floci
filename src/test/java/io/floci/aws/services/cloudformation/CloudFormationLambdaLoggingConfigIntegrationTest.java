package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code AWS::Lambda::Function.LoggingConfig} through CloudFormation. Measured on AWS (eu-west-1,
 * 2026-10-16): the function reads back the LogGroup the template names; a change to JSON with an
 * ApplicationLogLevel updates it in place, SystemLogLevel reading back INFO; and removing the
 * property puts it back on Text and {@code /aws/lambda/<name>}, again in place.
 */
@QuarkusTest
class CloudFormationLambdaLoggingConfigIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LAMBDA = "/2015-03-31/functions/";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void loggingConfigIsCreatedUpdatedAndRemovedInPlace() {
        String stackName = "cfn-lambda-logging-" + Long.toString(System.nanoTime(), 36);
        registerDeletion(stackName);

        deploy("CreateStack", stackName, template("""
                ,"LoggingConfig": {"LogGroup": "/floci/cfn-a"}
                """));
        String created = functionName(stackName);
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.LogGroup", equalTo("/floci/cfn-a"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue());

        deploy("UpdateStack", stackName, template("""
                ,"LoggingConfig": {"LogGroup": "/floci/cfn-b", "LogFormat": "JSON", "ApplicationLogLevel": "WARN"}
                """));
        assertEquals(created, functionName(stackName), "a changed LoggingConfig updates the function in place");
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("WARN"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"))
            .body("LoggingConfig.LogGroup", equalTo("/floci/cfn-b"));

        deploy("UpdateStack", stackName, template(""));
        assertEquals(created, functionName(stackName), "removing LoggingConfig updates the function in place");
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue())
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/" + created));
    }

    /**
     * A log level outside the enum fails the update, as Lambda refuses it, even under Text where
     * Lambda would not store a level: it must not pass the update check as an unchanged
     * LoggingConfig, and the function keeps its configuration.
     */
    @Test
    void anInvalidLogLevelFailsTheUpdate() {
        String stackName = "cfn-lambda-logging-level-" + Long.toString(System.nanoTime(), 36);
        registerDeletion(stackName);
        deploy("CreateStack", stackName, template(""));
        String created = functionName(stackName);

        submit("UpdateStack", stackName, template("""
                ,"LoggingConfig": {"LogFormat": "Text", "ApplicationLogLevel": "VERBOSE"}
                """));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertNotEquals("UPDATE_COMPLETE", state.status(), state.reason());
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue());
    }

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-18): a valid log level under Text passes the
     * template's validation but Lambda refuses it, so the update fails and the function keeps its
     * configuration.
     */
    @Test
    void aLogLevelUnderTextFailsTheUpdate() {
        String stackName = "cfn-lambda-logging-text-level-" + Long.toString(System.nanoTime(), 36);
        registerDeletion(stackName);
        deploy("CreateStack", stackName, template(""));
        String created = functionName(stackName);

        submit("UpdateStack", stackName, template("""
                ,"LoggingConfig": {"LogFormat": "Text", "ApplicationLogLevel": "WARN"}
                """));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertNotEquals("UPDATE_COMPLETE", state.status(), state.reason());
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue());
    }

    private void registerDeletion(String stackName) {
        cleanup.register(() -> {
            submit("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        });
    }

    private static String template(String loggingConfig) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(loggingConfig);
    }

    private static void deploy(String action, String stackName, String template) {
        submit(action, stackName, template);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CreateStack".equals(action) ? "CREATE_COMPLETE" : "UPDATE_COMPLETE", state.status(),
                state.reason());
    }

    private static void submit(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
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
