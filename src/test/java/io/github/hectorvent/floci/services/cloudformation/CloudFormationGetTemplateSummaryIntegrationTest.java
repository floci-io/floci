package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression for github.com/floci-io/floci/issues/1964: GetTemplateSummary fell through to
 * UnsupportedOperation, so sam deploy could not update an existing stack (it calls this action
 * before UpdateStack). CloudFormation is the Query API (form-encoded request, XML response); no
 * containers involved, so Docker-free.
 */
@QuarkusTest
class CloudFormationGetTemplateSummaryIntegrationTest {

    @Test
    void byTemplateBody_returnsResourceTypesParametersAndVersion() {
        String template = """
                {
                  "AWSTemplateFormatVersion": "2010-09-09",
                  "Description": "A simple bucket stack",
                  "Parameters": {
                    "BucketNameParam": {
                      "Type": "String",
                      "Default": "my-default-bucket",
                      "Description": "Name of the bucket"
                    }
                  },
                  "Resources": {
                    "MyBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {}
                    },
                    "MyQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {}
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Version>2010-09-09</Version>"))
            .body(containsString("<Description>A simple bucket stack</Description>"))
            .body(containsString("<member>AWS::S3::Bucket</member>"))
            .body(containsString("<member>AWS::SQS::Queue</member>"))
            .body(containsString("<ParameterKey>BucketNameParam</ParameterKey>"))
            .body(containsString("<DefaultValue>my-default-bucket</DefaultValue>"))
            .body(containsString("<ParameterType>String</ParameterType>"));
    }

    @Test
    void byStackName_reflectsTheDeployedStackTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "get-template-summary-stack-" + suffix;
        String template = """
                {
                  "Resources": {
                    "MyTopic": {
                      "Type": "AWS::SNS::Topic",
                      "Properties": {}
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>AWS::SNS::Topic</member>"));
    }

    @Test
    void declaresSamTransform_soSamCliCanRecognizeIt() {
        // The exact scenario from #1964: SAM templates declare this transform, and sam deploy
        // calls GetTemplateSummary as part of its update flow before it will call UpdateStack.
        String template = """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Resources": {
                    "MyFunction": {
                      "Type": "AWS::Serverless::Function",
                      "Properties": {}
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>AWS::Serverless-2016-10-31</member>"));
    }

    @Test
    void byStackName_onDeployedSamStack_stillReflectsOriginalTransformAndTypes() {
        // CreateStack expands AWS::Serverless::Function to AWS::Lambda::Function and drops the
        // Transform key from the stored (post-transform) template body. GetTemplateSummary by
        // StackName must still summarize the template as originally submitted, matching what
        // sam deploy expects to see back for a stack it is about to update.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "get-template-summary-sam-stack-" + suffix;
        String template = """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Resources": {
                    "MyFunction": {
                      "Type": "AWS::Serverless::Function",
                      "Properties": {
                        "Handler": "index.handler",
                        "Runtime": "nodejs22.x",
                        "InlineCode": "exports.handler = async () => {};"
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
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>AWS::Serverless-2016-10-31</member>"))
            .body(containsString("<member>AWS::Serverless::Function</member>"))
            .body(not(containsString("AWS::Lambda::Function")));
    }

    @Test
    void reportsCapabilityIam_whenTemplateHasIamResources() {
        String template = """
                {
                  "Resources": {
                    "MyRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": []
                        }
                      }
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>CAPABILITY_IAM</member>"))
            .body(containsString("AWS::IAM::Role"));
    }

    @Test
    void reportsCapabilityNamedIam_whenIamResourceHasExplicitName() {
        String template = """
                {
                  "Resources": {
                    "MyRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "my-explicit-role-name",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": []
                        }
                      }
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>CAPABILITY_NAMED_IAM</member>"))
            .body(not(containsString("<member>CAPABILITY_IAM</member>")));
    }

    @Test
    void reportsCapabilityNamedIam_whenRoleNameIsAnIntrinsicFunction() {
        // RoleName set via Fn::Sub (or Ref, Fn::Join, ...) only resolves at deploy time, but
        // CloudFormation still requires CAPABILITY_NAMED_IAM whenever the property is present at
        // all - this is the common real-world shape (SAM/CDK rarely hardcode literal names).
        String template = """
                {
                  "Resources": {
                    "MyRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": { "Fn::Sub": "${AWS::StackName}-role" },
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": []
                        }
                      }
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>CAPABILITY_NAMED_IAM</member>"))
            .body(not(containsString("<member>CAPABILITY_IAM</member>")));
    }

    @Test
    void withoutIamResources_reportsNoCapabilities() {
        String template = """
                {
                  "Resources": {
                    "MyBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {}
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("CAPABILITY_IAM")));
    }

    @Test
    void withoutAnyTemplateSource_returnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void unknownStackName_returnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("StackName", "this-stack-does-not-exist")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }
}
