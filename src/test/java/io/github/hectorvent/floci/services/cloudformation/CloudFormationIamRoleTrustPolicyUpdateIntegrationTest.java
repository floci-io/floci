package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression for github.com/floci-io/floci/issues/2084: updating a stack that changes an
 * existing {@code AWS::IAM::Role}'s {@code AssumeRolePolicyDocument} silently dropped the new
 * trust policy — the role-adoption path (triggered by {@code EntityAlreadyExists} on retry/update)
 * never applied the template's current document, even though the stack reported UPDATE_COMPLETE.
 * IAM/CloudFormation are pure JVM services (Query API, XML response); no containers, Docker-free.
 */
@QuarkusTest
class CloudFormationIamRoleTrustPolicyUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static String stackWithTrustPolicy(String roleName, String principalService) {
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
                            "Principal": {"Service": "%s"},
                            "Action": "sts:AssumeRole"
                          }]
                        }
                      }
                    }
                  }
                }
                """.formatted(roleName, principalService);
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

    private static void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    @Test
    void updateStack_iamRoleTrustPolicyChangeIsApplied() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "trust-policy-update-" + suffix;
        String roleName = "trust-policy-role-" + suffix;

        createStack(stackName, stackWithTrustPolicy(roleName, "lambda.amazonaws.com"));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRole")
            .formParam("RoleName", roleName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("lambda.amazonaws.com"));

        // Update: trust policy's principal changes from lambda to ec2 — the role already exists,
        // so this exercises the adoption path (EntityAlreadyExists) rather than a fresh create.
        updateStack(stackName, stackWithTrustPolicy(roleName, "ec2.amazonaws.com"));
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRole")
            .formParam("RoleName", roleName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("ec2.amazonaws.com"))
            .body(not(containsString("lambda.amazonaws.com")));
    }
}
