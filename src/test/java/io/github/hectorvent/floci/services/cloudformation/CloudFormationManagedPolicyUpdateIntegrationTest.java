package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

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

    @Inject
    CloudFormationService cloudFormationService;

    private String iam(String action, String... kv) {
        var req = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            req = req.formParam(kv[i], kv[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().asString();
    }

    @SuppressWarnings("unchecked")
    private void forgetPolicyId(String stackName, String logicalId) throws ReflectiveOperationException {
        Object target = cloudFormationService instanceof io.quarkus.arc.ClientProxy proxy
                ? proxy.arc_contextualInstance()
                : cloudFormationService;
        Field stacksField = CloudFormationService.class.getDeclaredField("stacks");
        stacksField.setAccessible(true);
        Map<String, Stack> stacks = (Map<String, Stack>) stacksField.get(target);
        Stack stack = stacks.values().stream()
                .filter(s -> stackName.equals(s.getStackName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Stack not found: " + stackName));
        stack.getResources().get(logicalId).getAttributes().remove("PolicyId");
    }

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

    @Test
    void updateRefusesToAdoptAPolicyRecreatedUnderTheStackOwnedName() throws ReflectiveOperationException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mp-recreate-" + suffix;
        String policyName = "mp-recreate-" + suffix;
        String roleName = "mp-recreate-role-" + suffix;
        String otherRole = "mp-recreate-other-" + suffix;
        String policyArn = policyArn(policyName);

        // Roles are plain IAM entities outside the stack (referenced by literal name, not Ref) so
        // this stack's only resource is the managed policy — isolating the assertion from the
        // separate, pre-existing "rollback not implemented for AWS::IAM::Role" limitation.
        iam("CreateRole", "RoleName", roleName, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");

        createStack(stackName, literalRoleManagedPolicyTemplate(policyName, "s3:GetObject", roleName));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // Simulate a stack resource persisted before PolicyId tracking existed.
        forgetPolicyId(stackName, "SessionPolicy");

        // An unrelated actor deletes and recreates the policy under the same name: same ARN,
        // different PolicyId, attached to a role this stack has never heard of.
        iam("DetachRolePolicy", "RoleName", roleName, "PolicyArn", policyArn);
        iam("DeletePolicy", "PolicyArn", policyArn);
        iam("CreatePolicy", "PolicyName", policyName, "PolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}");
        iam("CreateRole", "RoleName", otherRole, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");
        iam("AttachRolePolicy", "RoleName", otherRole, "PolicyArn", policyArn);

        // The stack's next pass must not silently mutate the policy it no longer owns.
        updateStack(stackName, literalRoleManagedPolicyTemplate(policyName, "s3:PutObject", roleName));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        // The recreated policy is untouched: still v1, still only attached to its own owner's role.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", policyArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DefaultVersionId>v1</DefaultVersionId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListEntitiesForPolicy")
            .formParam("PolicyArn", policyArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(otherRole))
            .body(not(containsString(roleName)));
    }

    private static String literalRoleManagedPolicyTemplate(String policyName, String action, String... roleNames) {
        String roleList = String.join(",", java.util.Arrays.stream(roleNames)
                .map(name -> "\"" + name + "\"").toList());
        return """
                {
                  "Resources": {
                    "SessionPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "%s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "%s", "Resource": "*"}]
                        },
                        "Roles": [%s]
                      }
                    }
                  }
                }
                """.formatted(policyName, action, roleList);
    }

    @Test
    void failedRoleAttachOnUpdateRestoresThePriorVersionAndDetachedRoles() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mp-partial-" + suffix;
        String policyName = "mp-partial-" + suffix;
        String oldRole = "mp-partial-old-" + suffix;
        String badRole = "mp-partial-missing-" + suffix;

        // Roles are plain IAM entities outside the stack (referenced by literal name, not Ref) so
        // this stack's only resource is the managed policy — isolating the assertion from the
        // separate, pre-existing "rollback not implemented for AWS::IAM::Role" limitation.
        iam("CreateRole", "RoleName", oldRole, "AssumeRolePolicyDocument",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}");

        createStack(stackName, literalRoleManagedPolicyTemplate(policyName, "s3:GetObject", oldRole));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // Retarget to a role that does not exist: AttachRolePolicy fails after the update path
        // has already replaced the default version and detached the old (now-obsolete) role.
        updateStack(stackName, literalRoleManagedPolicyTemplate(policyName, "s3:PutObject", badRole));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        // The default version reverted to the original document (v1), not left on the stray v2
        // that was published before the attach failure.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", policyArn(policyName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DefaultVersionId>v1</DefaultVersionId>"));

        // The old role, detached mid-update as "obsolete", is reattached rather than left orphaned.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", oldRole)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(policyArn(policyName)));
    }
}
