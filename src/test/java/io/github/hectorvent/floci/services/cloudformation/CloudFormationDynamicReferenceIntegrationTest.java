package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * CloudFormation resolves {@code {{resolve:...}}} dynamic references for any string property in a
 * template, not only the RDS master-credential properties that first needed them (issue #2213).
 * These tests exercise that general path end to end through a real stack deploy, using a Lambda
 * {@code Environment.Variables} entry the way the AWS documentation's own example does.
 */
@QuarkusTest
class CloudFormationDynamicReferenceIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_lambdaEnvironmentVariableResolvesSsmDynamicReference() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/cfn-dynref/url",
                    "Value": "https://real.example.com",
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
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-dynref-ssm-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "URL": "{{resolve:ssm:/cfn-dynref/url}}"
                      }
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-dynref-ssm-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-dynref-ssm-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-dynref-ssm-func")
        .then()
            .statusCode(200)
            .body("Configuration.Environment.Variables.URL", equalTo("https://real.example.com"));
    }

    @Test
    void createStack_lambdaEnvironmentVariableResolvesSecretsManagerDynamicReference() {
        given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "cfn-dynref-secret",
                    "SecretString": "{\\"apiKey\\":\\"s3cr3t-value\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-dynref-sm-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "API_KEY": "{{resolve:secretsmanager:cfn-dynref-secret:SecretString:apiKey}}"
                      }
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-dynref-sm-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-dynref-sm-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-dynref-sm-func")
        .then()
            .statusCode(200)
            .body("Configuration.Environment.Variables.API_KEY", equalTo("s3cr3t-value"));
    }

    @Test
    void createStack_snsTopicNameResolvesSsmDynamicReferenceThroughResolveOptional() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/cfn-dynref/topic-name",
                    "Value": "cfn-dynref-resolved-topic",
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
              "Resources": {
                "MyTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {
                    "TopicName": "{{resolve:ssm:/cfn-dynref/topic-name}}"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-dynref-sns-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-dynref-sns-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListTopics")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cfn-dynref-resolved-topic"))
            .body(org.hamcrest.Matchers.not(containsString("{{resolve:ssm:")));
    }

    @Test
    void createStack_ssmSecureDynamicReferenceOnANonRdsResourceFailsResourceCreation() {
        String template = """
            {
              "Resources": {
                "MyTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {
                    "TopicName": "{{resolve:ssm-secure:/cfn-dynref/topic-name}}"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-dynref-ssm-secure-non-rds-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-dynref-ssm-secure-non-rds-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));
    }

    @Test
    void createStack_lambdaEnvironmentVariableLeavesPlainLiteralStringUntouched() {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-dynref-literal-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "PLAIN": "just-a-normal-value",
                        "BRACES": "not-a-dynamic-reference-{{example}}"
                      }
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-dynref-literal-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-dynref-literal-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-dynref-literal-func")
        .then()
            .statusCode(200)
            .body("Configuration.Environment.Variables.PLAIN", equalTo("just-a-normal-value"))
            .body("Configuration.Environment.Variables.BRACES",
                    equalTo("not-a-dynamic-reference-{{example}}"));
    }
}
