package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * When an update references a missing SSM parameter, resolution fails before any resource is
 * touched — but {@code executeTemplate} used to overwrite the stack's live parameter map with the
 * attempted (unresolved) values first. After the rollback, {@code DescribeStacks} must still
 * report the last successfully deployed parameter values, not the failed update's inputs.
 */
@QuarkusTest
class CloudFormationUpdateRollbackParametersTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void failedSsmParameterUpdate_retainsLastSuccessfulParameterValues() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/cfn/test/rollback-suffix",
                    "Value": "orders-primary",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Parameters": {
                "QueueSuffix": {
                  "Type": "AWS::SSM::Parameter::Value<String>"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cfn-rollback-${QueueSuffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-rollback-stack")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", "/cfn/test/rollback-suffix")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-rollback-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<ParameterValue>/cfn/test/rollback-suffix</ParameterValue>"));

        // Update references an SSM parameter name that was never put — resolution fails and the
        // update must roll back before any resource change is committed.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "ssm-rollback-stack")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", "/cfn/test/does-not-exist")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-rollback-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_COMPLETE</StackStatus>"))
            .body(containsString("<ParameterValue>/cfn/test/rollback-suffix</ParameterValue>"))
            .body(not(containsString("<ParameterValue>/cfn/test/does-not-exist</ParameterValue>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "ssm-rollback-stack")
        .when()
            .post("/");
    }

    /**
     * When resource rollback itself also fails (not just the template update), the stack lands in
     * UPDATE_ROLLBACK_FAILED rather than UPDATE_ROLLBACK_COMPLETE. Parameter restoration must not be
     * skipped in that path — the last successfully deployed values are independent of whether the
     * resource rollback succeeded.
     */
    @Test
    void failedUpdateWithFailedResourceRollback_stillRetainsLastSuccessfulParameterValues() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "rollback-failed-params-stack-" + suffix;
        String parameterName = "/cfn/test/rollback-failed-params-" + suffix;
        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String"}
              },
              "Resources": {
                "Parameter": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "%s",
                    "Type": "String",
                    "Value": {"Fn::Sub": "value-${Suffix}"}
                  }
                }
                %s
              }
            }
            """;
        String initialTemplate = template.formatted(parameterName, "");
        String failingTemplate = template.formatted(parameterName, """
            ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "Parameter",
              "Properties": {
                "Name": "rollback-failed-params-secret-%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }
            """.formatted(suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "one")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The update changes Suffix (which the Parameter resource's Value depends on) and also
        // introduces BadSecret, which fails to create. Rollback must then also fail: rollback isn't
        // implemented for AWS::SSM::Parameter, so the stack lands in UPDATE_ROLLBACK_FAILED.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", failingTemplate)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "two")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_FAILED</StackStatus>"))
            .body(containsString("<ParameterValue>one</ParameterValue>"))
            .body(not(containsString("<ParameterValue>two</ParameterValue>")));
    }
}
