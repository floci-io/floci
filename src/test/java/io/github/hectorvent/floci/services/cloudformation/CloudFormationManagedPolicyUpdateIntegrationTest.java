package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression for the LZA OperationsStack second-pass failure: a stack UPDATE whose template
 * changes an {@code AWS::IAM::ManagedPolicy}'s {@code PolicyDocument} must adopt the policy the
 * stack already owns and publish the new document as the default version — exactly what real
 * CloudFormation does, since PolicyDocument is a mutable property. Previously the provisioner
 * unconditionally called CreatePolicy, which failed with "Policy ... already exists" and rolled
 * the stack back to UPDATE_ROLLBACK_COMPLETE.
 */
@QuarkusTest
class CloudFormationManagedPolicyUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";
    private static final String ACCOUNT = "000000000000";

    private static String managedPolicyTemplate(String policyName, String roleName, String action) {
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
                            "Principal": {"Service": "ec2.amazonaws.com"},
                            "Action": "sts:AssumeRole"
                          }]
                        }
                      }
                    },
                    "SessionPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "%s", "Resource": "*"}]
                        },
                        "Roles": [{"Ref": "Role"}]
                      }
                    }
                  }
                }
                """.formatted(roleName, policyName, action);
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

    private static String policyArn(String policyName) {
        return "arn:aws:iam::" + ACCOUNT + ":policy/" + policyName;
    }

    @Test
    void updateWithChangedPolicyDocumentAdoptsExistingPolicy() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mp-update-" + suffix;
        String policyName = "mp-update-" + suffix;
        String roleName = "mp-update-role-" + suffix;

        createStack(stackName, managedPolicyTemplate(policyName, roleName, "s3:GetObject"));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // Second pipeline pass: same stack, same policy name, changed document.
        updateStack(stackName, managedPolicyTemplate(policyName, roleName, "s3:PutObject"));
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        // The new document is the default version now.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", policyArn(policyName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DefaultVersionId>v2</DefaultVersionId>"));

        // The role attachment survived the update.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", roleName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(policyArn(policyName)));
    }

    @Test
    void repeatedUpdatesDoNotExhaustThePolicyVersionLimit() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mp-repeat-" + suffix;
        String policyName = "mp-repeat-" + suffix;
        String roleName = "mp-repeat-role-" + suffix;

        createStack(stackName, managedPolicyTemplate(policyName, roleName, "s3:GetObject"));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // IAM caps a managed policy at 5 versions; CloudFormation prunes old ones. Six
        // updates would fail with LimitExceeded if the provisioner never pruned.
        String[] actions = {"s3:PutObject", "s3:DeleteObject", "s3:ListBucket",
                "sqs:SendMessage", "sns:Publish", "logs:PutLogEvents"};
        for (String action : actions) {
            updateStack(stackName, managedPolicyTemplate(policyName, roleName, action));
            assertStackStatus(stackName, "UPDATE_COMPLETE");
        }
    }

    @Test
    void updateRetargetingRolesDetachesTheRemovedRole() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mp-retarget-" + suffix;
        String policyName = "mp-retarget-" + suffix;
        String oldRole = "mp-retarget-old-" + suffix;
        String newRole = "mp-retarget-new-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateRole")
            .formParam("RoleName", newRole)
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
        .when().post("/").then().statusCode(200);

        createStack(stackName, managedPolicyTemplate(policyName, oldRole, "s3:GetObject"));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        String retargeted = """
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
                            "Principal": {"Service": "ec2.amazonaws.com"},
                            "Action": "sts:AssumeRole"
                          }]
                        }
                      }
                    },
                    "SessionPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "s3:GetObject", "Resource": "*"}]
                        },
                        "Roles": ["%s"]
                      }
                    }
                  }
                }
                """.formatted(oldRole, policyName, newRole);
        updateStack(stackName, retargeted);
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", oldRole)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(policyArn(policyName))));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", newRole)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(policyArn(policyName)));
    }
}
