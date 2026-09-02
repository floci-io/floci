package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationSsmParameterIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_resolvesSsmTypedParameterValue() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/cfn/test/queue-suffix",
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
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/cfn/test/queue-suffix"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-${QueueSuffix}" }
                  }
                }
              },
              "Outputs": {
                "ResolvedSuffix": { "Value": { "Ref": "QueueSuffix" } }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>orders-primary</OutputValue>"))
            .body(not(containsString("<OutputValue>/cfn/test/queue-suffix</OutputValue>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cfn-ssm-orders-primary")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cfn-ssm-orders-primary"));
    }

    @Test
    void createStack_missingSsmParameterFailsWithValidationError() {
        String template = """
            {
              "Parameters": {
                "MissingParam": {
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/cfn/test/does-not-exist"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-missing-${MissingParam}" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-missing-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-missing-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString(
                    "Unable to fetch parameters [/cfn/test/does-not-exist] from parameter store for this account"));
    }
}
