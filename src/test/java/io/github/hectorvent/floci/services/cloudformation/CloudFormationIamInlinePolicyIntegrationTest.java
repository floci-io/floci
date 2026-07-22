package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for GH-1531: two CloudFormation stacks in the same account each declare an
 * {@code AWS::IAM::Policy} with the same auto-generated {@code PolicyName} (as CDK emits when two
 * stacks reuse a construct sub-tree — e.g. {@code IamTaskRoleDefaultPolicy<hash>}) but attach it to
 * different roles. {@code AWS::IAM::Policy} is an <em>inline</em> policy, scoped to its owning
 * principal, so both stacks must deploy. Previously Floci modelled it as a standalone (account-global)
 * managed policy, so the second stack failed with "Policy ... already exists" and rolled back.
 *
 * <p>Also asserts that {@code AWS::IAM::ManagedPolicy} remains a standalone managed policy with an ARN.
 * IAM is the Query API (form-encoded request, XML response); no containers involved, so Docker-free.
 */
@QuarkusTest
class CloudFormationIamInlinePolicyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static String stackWithInlinePolicy(String roleName, String policyName) {
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
                            "Principal": {"Service": "ecs-tasks.amazonaws.com"},
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
                            "Resource": "*"
                          }]
                        },
                        "Roles": [{"Ref": "Role"}]
                      }
                    }
                  }
                }
                """.formatted(roleName, policyName);
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

    private static void assertStackComplete(String stackName) {
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
    }

    @Test
    void twoStacksReusingSameInlinePolicyNameBothDeploy() {
        String suffix = Long.toString(System.nanoTime(), 36);
        // Same auto-generated policy name in both stacks — the exact GH-1531 collision.
        String sharedPolicyName = "IamTaskRoleDefaultPolicy" + suffix;
        String roleA = "role-a-" + suffix;
        String roleB = "role-b-" + suffix;

        createStack("cfn-iam-inline-a-" + suffix, stackWithInlinePolicy(roleA, sharedPolicyName));
        assertStackComplete("cfn-iam-inline-a-" + suffix);

        // Before the fix this second stack rolled back ("Policy ... already exists").
        createStack("cfn-iam-inline-b-" + suffix, stackWithInlinePolicy(roleB, sharedPolicyName));
        assertStackComplete("cfn-iam-inline-b-" + suffix);

        // Each role really carries the inline policy, scoped to that role.
        for (String roleName : new String[] {roleA, roleB}) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", sharedPolicyName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(sharedPolicyName))
                .body(containsString("s3:GetObject"));
        }
    }

    @Test
    void managedPolicyRemainsStandaloneWithArn() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String managedName = "managed-policy-" + suffix;
        String stackName = "cfn-iam-managed-" + suffix;

        String template = """
                {
                  "Resources": {
                    "ManagedPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Action": "s3:ListBucket",
                            "Resource": "*"
                          }]
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "PolicyArn": {"Value": {"Ref": "ManagedPolicy"}}
                  }
                }
                """.formatted(managedName);

        createStack(stackName, template);

        // Managed policy has an ARN (unlike inline), surfaced through Ref.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(":policy/" + managedName));
    }

    @Test
    void roleWithMissingManagedPolicyFailsAndRollsBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-iam-role-missing-policy-" + suffix;
        String missingArn = "arn:aws:iam::aws:policy/DefinitelyMissing-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "missing-policy-role-%s",
                        "ManagedPolicyArns": ["%s"]
                      }
                    }
                  }
                }
                """.formatted(suffix, missingArn);

        createStack(stackName, template);

        assertFailedResource(stackName, "Role", missingArn, "does not exist");
    }

    @Test
    void managedPolicyWithMissingRoleFailsAndRollsBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-iam-policy-missing-role-" + suffix;
        String missingRole = "missing-role-" + suffix;
        String template = """
                {
                  "Resources": {
                    "ManagedPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "missing-role-policy-%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "s3:GetObject", "Resource": "*"}]
                        },
                        "Roles": ["%s"]
                      }
                    }
                  }
                }
                """.formatted(suffix, missingRole);

        createStack(stackName, template);

        assertFailedResource(stackName, "ManagedPolicy", missingRole, "cannot be found");
    }

    @Test
    void deletingStackDetachesInlinePolicyFromSurvivingRole() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String roleName = "ext-role-" + suffix;
        String policyName = "InlinePolicy" + suffix;
        String stackName = "cfn-iam-inline-del-" + suffix;

        // Role owned OUTSIDE the stack, so it survives DeleteStack — this isolates the inline-policy
        // detach (deleteInlinePolicySafe/detachInline) from a cascade role delete.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
        .when().post("/").then().statusCode(200);

        String template = """
                {
                  "Resources": {
                    "DefaultPolicy": {
                      "Type": "AWS::IAM::Policy",
                      "Properties": {
                        "PolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "s3:GetObject", "Resource": "*"}]
                        },
                        "Roles": ["%s"]
                      }
                    }
                  }
                }
                """.formatted(policyName, roleName);

        createStack(stackName, template);
        assertStackComplete(stackName);

        // The inline policy is embedded in the pre-existing role.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyName", policyName)
        .when().post("/").then().statusCode(200).body(containsString("s3:GetObject"));

        // Deleting the stack must detach the inline policy from the surviving role (async delete).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);

        awaitInlinePolicyGone(roleName, policyName);

        // The role still exists ...
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRole")
            .formParam("RoleName", roleName)
        .when().post("/").then().statusCode(200).body(containsString(roleName));

        // ... but its inline policy is gone (NoSuchEntity, HTTP 404).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyName", policyName)
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));
    }

    /** DeleteStack runs asynchronously; poll until the inline policy has been detached (404) or time out. */
    private static void awaitInlinePolicyGone(String roleName, String policyName) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            int sc = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", policyName)
            .when().post("/").then().extract().statusCode();
            if (sc == 404) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for inline policy " + policyName
                + " to be detached from role " + roleName);
    }

    private static void assertFailedResource(String stackName, String logicalId,
                                             String expectedSubject, String expectedReason) {
        String stack = describeStack(stackName, "DescribeStacks");
        assertTrue(
                stack.contains("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"),
                "stack should roll back after the attachment failure: " + stack);
        assertFalse(
                stack.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                "stack must not report false success: " + stack);

        String resources = describeStack(stackName, "DescribeStackResources");
        assertTrue(
                resources.contains("<LogicalResourceId>" + logicalId + "</LogicalResourceId>")
                        && resources.contains("<ResourceStatus>CREATE_FAILED</ResourceStatus>"),
                "failed resource status should be visible: " + resources);

        String events = describeStack(stackName, "DescribeStackEvents");
        assertTrue(
                events.contains(expectedSubject) && events.contains(expectedReason),
                "failure reason should identify the missing attachment target: " + events);
    }

    private static String describeStack(String stackName, String action) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }
}
