package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation applies the inline {@code Policies} of an
 * AWS::IAM::Role (issue #1952): the policies must be visible through the IAM API,
 * intrinsics inside the policy documents must resolve, and DeleteStack must still
 * be able to remove the role.
 */
@QuarkusTest
class CloudFormationIamRoleInlinePoliciesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    @Test
    void createStackProvisionsInlineRolePoliciesAndDeleteStackRemovesRole() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String roleName = "cfn-inline-role-" + suffix;
        String bucketName = "cfn-inline-bucket-" + suffix;
        String stackName = "cfn-inline-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "DataBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {"BucketName": "%s"}
                    },
                    "AppRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Principal": {"Service": "lambda.amazonaws.com"},
                            "Action": "sts:AssumeRole"
                          }]
                        },
                        "Policies": [
                          {
                            "PolicyName": "bucket-read",
                            "PolicyDocument": {
                              "Version": "2012-10-17",
                              "Statement": [{
                                "Effect": "Allow",
                                "Action": "s3:GetObject",
                                "Resource": {"Fn::GetAtt": ["DataBucket", "Arn"]}
                              }]
                            }
                          },
                          {
                            "PolicyName": "log-write",
                            "PolicyDocument": {
                              "Version": "2012-10-17",
                              "Statement": [{
                                "Effect": "Allow",
                                "Action": "logs:PutLogEvents",
                                "Resource": "*"
                              }]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """.formatted(bucketName, roleName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Both inline policies exist on the role.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListRolePolicies")
            .formParam("RoleName", roleName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("bucket-read"))
            .body(containsString("log-write"));

        // The stored document resolved Fn::GetAtt to the bucket ARN.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyName", "bucket-read")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("s3:GetObject"))
            .body(containsString("arn:aws:s3:::" + bucketName))
            .body(not(containsString("Fn::GetAtt")));

        // DeleteStack must remove the role despite its inline policies.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRole")
            .formParam("RoleName", roleName)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }
}
