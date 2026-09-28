package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationPolicyDocumentIntrinsicsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static String stackWithSubShorthandPolicy(String roleName, String bucketName, String policyName) {
        return """
                {
                  "Resources": {
                    "StagingBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {"BucketName": "%s"}
                    },
                    "Role": {
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
                        }
                      }
                    },
                    "DefaultPolicy": {
                      "Type": "AWS::IAM::Policy",
                      "Properties": {
                        "PolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Sid": {"Fn::Sub": "keep${!ThisLiteral}asis"},
                            "Effect": "Allow",
                            "Action": ["s3:GetObject", "s3:ListBucket"],
                            "Resource": [
                              {"Fn::Sub": "${StagingBucket.Arn}"},
                              {"Fn::Sub": "${StagingBucket.Arn}/*"}
                            ]
                          }]
                        },
                        "Roles": [{"Ref": "Role"}]
                      }
                    }
                  }
                }
                """.formatted(bucketName, roleName, policyName);
    }

    /** Same shape, but the referenced logical id does not exist anywhere in the template. */
    private static String stackWithUnresolvablePolicyResource(String roleName, String policyName) {
        return """
                {
                  "Resources": {
                    "Role": {
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
                        }
                      }
                    },
                    "DefaultPolicy": {
                      "Type": "AWS::IAM::Policy",
                      "Properties": {
                        "PolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Action": "s3:GetObject",
                            "Resource": {"Fn::GetAtt": ["NoSuchBucket", "Arn"]}
                          }]
                        },
                        "Roles": [{"Ref": "Role"}]
                      }
                    }
                  }
                }
                """.formatted(roleName, policyName);
    }

    /** An inline user policy whose document references a logical id that does not exist. */
    private static String stackWithUnresolvableUserPolicy(String userName, String policyName) {
        return """
                {
                  "Resources": {
                    "User": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s",
                        "Policies": [{
                          "PolicyName": "%s",
                          "PolicyDocument": {
                            "Version": "2012-10-17",
                            "Statement": [{
                              "Effect": "Allow",
                              "Action": "s3:GetObject",
                              "Resource": {"Fn::GetAtt": ["NoSuchBucket", "Arn"]}
                            }]
                          }
                        }]
                      }
                    }
                  }
                }
                """.formatted(userName, policyName);
    }

    private static void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static Response cfnQuery(String action, String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
        .when()
            .post("/");
    }

    @Test
    void subGetAttShorthandInAPolicyDocumentResolvesToTheRealArn() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-sub-shorthand-" + suffix;
        String bucketName = "sub-shorthand-bucket-" + suffix;
        String roleName = "sub-shorthand-role-" + suffix;
        String policyName = "sub-shorthand-policy-" + suffix;

        createStack(stackName, stackWithSubShorthandPolicy(roleName, bucketName, policyName));
        cfnQuery("DescribeStacks", stackName).then().statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        String storedPolicy = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyName", policyName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // The stored document names the real bucket, both bucket-level and object-level.
        assertTrue(storedPolicy.contains("arn:aws:s3:::" + bucketName),
                "expected the resolved bucket ARN in the stored policy, got: " + storedPolicy);
        assertTrue(storedPolicy.contains("arn:aws:s3:::" + bucketName + "/*"),
                "expected the resolved object ARN in the stored policy, got: " + storedPolicy);
        // ...and not the ARN-shaped literal the broken shorthand produced.
        assertFalse(storedPolicy.contains("StagingBucket.Arn"),
                "policy still carries the unresolved shorthand: " + storedPolicy);
        // ${!Literal} is an escape, not a GetAtt: it survives verbatim, minus the bang.
        assertTrue(storedPolicy.contains("keep${ThisLiteral}asis"),
                "expected the Fn::Sub literal escape to survive, got: " + storedPolicy);
    }

    @Test
    void unresolvableIntrinsicInAPolicyDocumentFailsTheResourceInsteadOfStoringAName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-unresolvable-policy-" + suffix;
        String roleName = "unresolvable-role-" + suffix;
        String policyName = "unresolvable-policy-" + suffix;

        createStack(stackName, stackWithUnresolvablePolicyResource(roleName, policyName));

        String status = cfnQuery("DescribeStacks", stackName).then().statusCode(200).extract().asString();
        assertFalse(status.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                "an unresolved intrinsic in a policy document must not deploy: " + status);

        String events = cfnQuery("DescribeStackEvents", stackName).then().statusCode(200).extract().asString();
        assertTrue(events.contains("unresolved CloudFormation intrinsics"),
                "expected a failure reason naming the unresolved intrinsic, got: " + events);
        assertTrue(events.contains("NoSuchBucket.Arn"),
                "expected the failure reason to name the intrinsic that failed, got: " + events);
    }

    @Test
    void unresolvableIntrinsicInAnInlineUserPolicyFailsTheResourceInsteadOfStoringAName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-unresolvable-user-policy-" + suffix;
        String userName = "unresolvable-user-" + suffix;
        String policyName = "unresolvable-user-policy-" + suffix;

        createStack(stackName, stackWithUnresolvableUserPolicy(userName, policyName));

        String status = cfnQuery("DescribeStacks", stackName).then().statusCode(200).extract().asString();
        assertFalse(status.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                "an unresolved intrinsic in an inline user policy must not deploy: " + status);

        String events = cfnQuery("DescribeStackEvents", stackName).then().statusCode(200).extract().asString();
        assertTrue(events.contains("unresolved CloudFormation intrinsics"),
                "expected a failure reason naming the unresolved intrinsic, got: " + events);
        assertTrue(events.contains("NoSuchBucket.Arn"),
                "expected the failure reason to name the intrinsic that failed, got: " + events);
    }
}
