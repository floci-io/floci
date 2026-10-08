package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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

    @Test
    void createStack_secretsManagerReferenceIsNotAPlaintextParameter() {
        given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(SSM_CONTENT_TYPE)
            .body("{\"Name\": \"cfn-ssm-ref-secret\", \"SecretString\": \"s3cr3t\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        String template = """
            {
              "Parameters": {
                "Secret": {
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/aws/reference/secretsmanager/cfn-ssm-ref-secret"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-ref-${Secret}" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-secret-ref-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-secret-ref-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString("Unable to fetch parameters [/aws/reference/secretsmanager/cfn-ssm-ref-secret]"));
    }

    @Test
    void createStack_parameterArnAttributeMatchesTheGetParameterArn() {
        String suffix = Long.toString(System.nanoTime());
        String stackName = "ssm-param-arn-" + suffix;
        String plain = "cfn-arn-plain-" + suffix;
        String nested = "/cfn-arn/nested-" + suffix;
        String template = """
            {
              "Resources": {
                "Plain": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": { "Name": "%s", "Type": "String", "Value": "v" }
                },
                "Nested": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": { "Name": "%s", "Type": "String", "Value": "v" }
                }
              },
              "Outputs": {
                "PlainArn": { "Value": { "Fn::GetAtt": ["Plain", "Arn"] } },
                "NestedArn": { "Value": { "Fn::GetAtt": ["Nested", "Arn"] } }
              }
            }
            """.formatted(plain, nested);

        cfn("CreateStack", stackName).formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("CREATE_COMPLETE"));

        XmlPath outputs = cfn("DescribeStacks", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        assertArnMatchesGetParameter(output(outputs, "PlainArn"), plain);
        assertArnMatchesGetParameter(output(outputs, "NestedArn"), nested);

        cfn("DeleteStack", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static RequestSpecification cfn(String action, String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName);
    }

    private static String output(XmlPath describe, String key) {
        return describe.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void assertArnMatchesGetParameter(String arn, String name) {
        assertThat(arn, allOf(containsString(":parameter/"), not(containsString("//"))));
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Name\": \"" + name + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.ARN", equalTo(arn));
    }
}
