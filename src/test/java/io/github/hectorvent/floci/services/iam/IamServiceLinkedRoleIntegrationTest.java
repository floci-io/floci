package io.github.hectorvent.floci.services.iam;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * Service-linked roles exist so Terraform's aws_iam_service_linked_role can apply and destroy
 * against the emulator: the create must succeed, the role must be readable afterwards, and the
 * delete must hand back a task id whose status can be polled.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamServiceLinkedRoleIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String SERVICE = "es.amazonaws.com";

    private static final String OTHER_ACCOUNT_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=222222222222/20260227/us-east-1/iam/aws4_request";

    private static String deletionTaskId;

    @Test
    @Order(1)
    void createServiceLinkedRolePlacesItUnderTheServiceRolePath() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", SERVICE)
            .formParam("Description", "Managed by the linked service")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForEs"))
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.Path",
                    equalTo("/aws-service-role/" + SERVICE + "/"))
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::000000000000:role/aws-service-role/" + SERVICE + "/AWSServiceRoleForEs"))
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleId",
                    startsWith("AROA"));
    }

    @Test
    @Order(2)
    void theRoleIsReadableAfterwards() {
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "AWSServiceRoleForEs")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.Path",
                    equalTo("/aws-service-role/" + SERVICE + "/"));
    }

    @Test
    @Order(3)
    void repeatingTheRequestWithoutASuffixIsRejectedAsADuplicate() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", SERVICE)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            // Not EntityAlreadyExists: that is createRole's generic answer and is absent from this
            // action's published error list, which does carry InvalidInput.
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    @Order(4)
    void aCustomSuffixMakesTheSecondRoleDistinct() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", SERVICE)
            .formParam("CustomSuffix", "debug")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForEs_debug"));
    }

    @Test
    @Order(5)
    void aMissingServiceNameIsRejected() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    @Order(6)
    void deleteReturnsATaskIdInTheDocumentedFormat() {
        deletionTaskId = given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "AWSServiceRoleForEs_debug")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // AWS documents task/aws-service-role/<service-principal-name>/<role-name>/<task-uuid>
            .body("DeleteServiceLinkedRoleResponse.DeleteServiceLinkedRoleResult.DeletionTaskId",
                    matchesRegex("task/aws-service-role/\\Q" + SERVICE + "\\E/AWSServiceRoleForEs_debug/"
                            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            .extract().path("DeleteServiceLinkedRoleResponse.DeleteServiceLinkedRoleResult.DeletionTaskId");
    }

    @Test
    @Order(7)
    void theDeletedRoleIsGone() {
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "AWSServiceRoleForEs_debug")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(8)
    void theDeletionStatusIsSucceeded() {
        given()
            .formParam("Action", "GetServiceLinkedRoleDeletionStatus")
            .formParam("DeletionTaskId", deletionTaskId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetServiceLinkedRoleDeletionStatusResponse"
                            + ".GetServiceLinkedRoleDeletionStatusResult.Status",
                    equalTo("SUCCEEDED"));
    }

    @Test
    @Order(9)
    void anUnknownDeletionTaskIsRejected() {
        given()
            .formParam("Action", "GetServiceLinkedRoleDeletionStatus")
            .formParam("DeletionTaskId", "task/aws-service-role/es.amazonaws.com/Nope/"
                    + "00000000-0000-0000-0000-000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * CreateRole does not reserve the service-role prefix, so a caller can park an ordinary role at
     * exactly {@code /aws-service-role/}. Recovering the principal from that path leaves an empty
     * segment, which must be rejected rather than crashing out of a substring.
     */
    @Test
    @Order(11)
    void deletingARoleParkedAtTheBareServiceRolePathIsRejected() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "ParkedAtThePrefix")
            .formParam("Path", "/aws-service-role/")
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "ParkedAtThePrefix")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /** An all-dots service name matches AWS's own parameter pattern, so it must 400, never 500. */
    @Test
    @Order(12)
    void anAllDotsServiceNameIsRejected() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", ".")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    /**
     * rds.amazonaws.com and rds.application-autoscaling.amazonaws.com are distinct roles on AWS. A
     * config declaring both is exactly this issue's use case, so they must not collide on one name.
     */
    @Test
    @Order(13)
    void principalsSharingALeadingLabelGetDistinctRoles() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "rds.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForRds"));

        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "rds.application-autoscaling.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForRdsApplicationAutoscaling"));
    }

    /**
     * Terraform recovers custom_suffix by splitting the role name on an underscore, and that
     * attribute forces replacement — a name joined any other way never converges.
     */
    @Test
    @Order(14)
    void theCustomSuffixIsJoinedWithAnUnderscore() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "autoscaling.amazonaws.com")
            .formParam("CustomSuffix", "CustomResource")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForAutoScaling_CustomResource"));
    }

    /** AWS constrains AWSServiceName to [\w+=,.@-]{1,128}; anything else must not reach storage. */
    @Test
    @Order(15)
    void aServiceNameOutsideTheAwsPatternIsRejected() {
        for (String bad : new String[]{"x\",\"AWS\":\"*", "a\"b.amazonaws.com", " ", "a/b.amazonaws.com",
                "x".repeat(129)}) {
            given()
                .formParam("Action", "CreateServiceLinkedRole")
                .formParam("AWSServiceName", bad)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        }
    }

    /** The deletion task is account-scoped storage, so another account must not resolve it. */
    @Test
    @Order(16)
    void aDeletionTaskIsNotVisibleToAnotherAccount() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "kafka.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String taskId = given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "AWSServiceRoleForKafka")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DeleteServiceLinkedRoleResponse.DeleteServiceLinkedRoleResult.DeletionTaskId");

        given()
            .formParam("Action", "GetServiceLinkedRoleDeletionStatus")
            .formParam("DeletionTaskId", taskId)
            .header("Authorization", OTHER_ACCOUNT_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * CreateRole accepts the service-role prefix, so path is not evidence of how a role was made.
     * An ordinary role sitting at a well-formed service-linked path must survive this action.
     */
    @Test
    @Order(17)
    void anOrdinaryRoleAtAServiceLinkedPathIsNotDeletable() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "ImpostorAtTheServicePath")
            .formParam("Path", "/aws-service-role/es.amazonaws.com/")
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "ImpostorAtTheServicePath")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));

        // ...and it is still there.
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "ImpostorAtTheServicePath")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void deletingAnOrdinaryRoleThroughThisActionIsRejected() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "NotServiceLinked")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "NotServiceLinked")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    private static void createServiceLinkedRole(String principal) {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", principal)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(18)
    void attachingAManagedPolicyToAServiceLinkedRoleIsRejected() {
        createServiceLinkedRole("attachprobe.amazonaws.com");

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "AWSServiceRoleForAttachprobe")
            .formParam("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnmodifiableEntity"));
    }

    @Test
    @Order(19)
    void embeddingAnInlinePolicyInAServiceLinkedRoleIsRejected() {
        createServiceLinkedRole("putprobe.amazonaws.com");

        given()
            .formParam("Action", "PutRolePolicy")
            .formParam("RoleName", "AWSServiceRoleForPutprobe")
            .formParam("PolicyName", "inline")
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnmodifiableEntity"));
    }

    @Test
    @Order(20)
    void deleteRoleOnAServiceLinkedRoleIsRejectedAndLeavesTheRoleInPlace() {
        createServiceLinkedRole("delroleprobe.amazonaws.com");

        given()
            .formParam("Action", "DeleteRole")
            .formParam("RoleName", "AWSServiceRoleForDelroleprobe")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnmodifiableEntity"));

        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "AWSServiceRoleForDelroleprobe")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void deleteServiceLinkedRoleStillRemovesARoleThatDeleteRoleRefuses() {
        createServiceLinkedRole("slrdelete.amazonaws.com");

        given()
            .formParam("Action", "DeleteServiceLinkedRole")
            .formParam("RoleName", "AWSServiceRoleForSlrdelete")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", "AWSServiceRoleForSlrdelete")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    private static void refusedAsUnmodifiable(String action, String... formParams) {
        var request = given().header("Authorization", AUTH_HEADER).formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("UnmodifiableEntity"));
    }

    /** The mark has to hold across every action AWS protects, not just the delete path. */
    @Test
    @Order(24)
    void theOtherRoleActionsAwsProtectsAreAlsoRefused() {
        createServiceLinkedRole("protectprobe.amazonaws.com");
        String roleName = "AWSServiceRoleForProtectprobe";
        String readOnly = "arn:aws:iam::aws:policy/ReadOnlyAccess";

        refusedAsUnmodifiable("UpdateRole", "RoleName", roleName, "Description", "hijacked");
        refusedAsUnmodifiable("UpdateAssumeRolePolicy", "RoleName", roleName,
                "PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        refusedAsUnmodifiable("DetachRolePolicy", "RoleName", roleName, "PolicyArn", readOnly);
        refusedAsUnmodifiable("DeleteRolePolicy", "RoleName", roleName, "PolicyName", "inline");
        refusedAsUnmodifiable("PutRolePermissionsBoundary", "RoleName", roleName,
                "PermissionsBoundary", readOnly);
        refusedAsUnmodifiable("DeleteRolePermissionsBoundary", "RoleName", roleName);

        given()
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", "protectprobe-profile")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        refusedAsUnmodifiable("AddRoleToInstanceProfile",
                "InstanceProfileName", "protectprobe-profile", "RoleName", roleName);
        refusedAsUnmodifiable("RemoveRoleFromInstanceProfile",
                "InstanceProfileName", "protectprobe-profile", "RoleName", roleName);

        // The trust policy is the one an attacker would rewrite, so pin that it survived intact.
        given()
            .formParam("Action", "GetRole")
            .formParam("RoleName", roleName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                    containsString("protectprobe.amazonaws.com"));
    }

    /** AWS leaves tagging open on a service-linked role; the guard must not overreach. */
    @Test
    @Order(25)
    void taggingAServiceLinkedRoleIsStillAllowed() {
        createServiceLinkedRole("tagprobe.amazonaws.com");

        given()
            .formParam("Action", "TagRole")
            .formParam("RoleName", "AWSServiceRoleForTagprobe")
            .formParam("Tags.member.1.Key", "team")
            .formParam("Tags.member.1.Value", "platform")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(22)
    void aCustomSuffixOutsideTheAllowedCharactersIsRejected() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "suffixprobe.amazonaws.com")
            .formParam("CustomSuffix", "a/b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    @Order(23)
    void aPrincipalDerivingARoleNamePastTheLengthLimitIsRejected() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "a".repeat(114) + ".amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    @Order(24)
    void cloud9UsesTheAwsCanonicalRoleName() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "cloud9.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForAWSCloud9"))
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::000000000000:role/aws-service-role/cloud9.amazonaws.com/"
                            + "AWSServiceRoleForAWSCloud9"));
    }
}
