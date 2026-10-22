package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.CloudFormationSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.cloudformation.CloudFormationLambdaMissingS3CodeIntegrationTest.CFN_AUTH;
import static io.github.hectorvent.floci.services.cloudformation.CloudFormationLambdaMissingS3CodeIntegrationTest.createStack;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The hard gate a pipeline can set: with
 * {@code floci.services.cloudformation.allow-stub-unsupported-resource-types} off, a resource type
 * Floci has no provisioner for fails instead of being stubbed, and the stack rolls back rather than
 * reporting a green deployment of something that was never created.
 *
 * <p>A separate top-level class rather than a nested one, because {@code @TestProfile} applies per
 * test class and Surefire discovers test classes by file name: a nested class carrying the profile
 * compiles and passes when targeted directly, but is silently skipped in a full {@code mvn test}.
 *
 * <p>The setting comes from {@link CloudFormationSettingsProfile}, which this class shares with the
 * other CloudFormation classes that change a default.
 */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
class CloudFormationUnsupportedResourceTypeStrictIntegrationTest {

    @Test
    void strictProfileRollsTheStackBack() {
        String stackName = "cfn-unsupported-strict-" + Long.toString(System.nanoTime(), 36);

        createStack(stackName, """
                {
                  "Resources": {
                    "MyThing": {
                      "Type": "AWS::Fake::Thing",
                      "Properties": {}
                    }
                  }
                }
                """);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"))
            .body(containsString("The following resource(s) failed to create: [MyThing]. Rollback requested by user."));
        assertThat(CfnStackWaits.failedResourceReason(stackName, CFN_AUTH),
                containsString("Resource type AWS::Fake::Thing is not supported by Floci."));
    }

    /**
     * The same gate covers a SAM function event the transform cannot expand: the function alone
     * would deploy green with its trigger missing, so the transform fails the stack instead.
     */
    @Test
    void strictProfileFailsASamFunctionWhoseEventIsNotExpanded() {
        String stackName = "cfn-sam-event-strict-" + Long.toString(System.nanoTime(), 36);

        createStack(stackName, """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Resources": {
                    "Uploads": {"Type": "AWS::S3::Bucket"},
                    "ThumbnailFunction": {
                      "Type": "AWS::Serverless::Function",
                      "Properties": {
                        "Handler": "index.handler",
                        "Runtime": "nodejs22.x",
                        "InlineCode": "exports.handler = async () => ({});",
                        "Events": {
                          "Upload": {
                            "Type": "S3",
                            "Properties": {"Bucket": {"Ref": "Uploads"}, "Events": "s3:ObjectCreated:*"}
                          }
                        }
                      }
                    }
                  }
                }
                """);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertThat(state.status(), equalTo("CREATE_FAILED"));
        assertThat(state.reason(), containsString(
                "SAM event Upload of type S3 on function ThumbnailFunction is not supported by Floci."));
    }

    /**
     * An Api event bound to an explicit API whose DefinitionBody lists its path and method is served
     * by that definition's routes, so the gate lets the stack deploy and nothing is reported.
     */
    @Test
    void strictProfileDeploysAnApiEventServedByTheExplicitApisDefinitionBody() {
        String stackName = "cfn-sam-explicit-api-strict-" + Long.toString(System.nanoTime(), 36);

        createStack(stackName, """
                {
                  "Transform": "AWS::Serverless-2016-10-31",
                  "Resources": {
                    "ItemsApi": {
                      "Type": "AWS::Serverless::Api",
                      "Properties": {
                        "StageName": "prod",
                        "DefinitionBody": {
                          "swagger": "2.0",
                          "info": {"title": "items", "version": "1.0"},
                          "paths": {
                            "/items": {
                              "get": {
                                "x-amazon-apigateway-integration": {
                                  "type": "aws_proxy",
                                  "httpMethod": "POST",
                                  "uri": {"Fn::Sub": "arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${ItemsFunction.Arn}/invocations"}
                                }
                              }
                            }
                          }
                        }
                      }
                    },
                    "ItemsFunction": {
                      "Type": "AWS::Serverless::Function",
                      "Properties": {
                        "Handler": "index.handler",
                        "Runtime": "nodejs22.x",
                        "InlineCode": "exports.handler = async () => ({});",
                        "Events": {
                          "Get": {
                            "Type": "Api",
                            "Properties": {"RestApiId": {"Ref": "ItemsApi"}, "Path": "/items", "Method": "get"}
                          }
                        }
                      }
                    }
                  }
                }
                """);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertThat(state.reason(), state.status(), equalTo("CREATE_COMPLETE"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResource")
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "ItemsFunction")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResourceStatus>CREATE_COMPLETE</ResourceStatus>"))
            .body(not(containsString("<ResourceStatusReason>")));
    }
}
