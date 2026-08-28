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
}
