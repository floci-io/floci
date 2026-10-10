package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String SERVICE_LINKED_ROLE_PATH = "/aws-service-role/";

    /** Created by the protection test and the only entity here that is not a role. */
    private static final String PROTECTION_PROBE_PROFILE = "protectprobe-profile";

    private static final String OTHER_ACCOUNT_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=222222222222/20260227/us-east-1/iam/aws4_request";

    private static String deletionTaskId;

    /**
     * Set by the test that creates the profile. A focused run of one method still fires the
     * teardown, so without this it would ask for a profile nothing had made.
     */
    private static boolean protectionProfileCreated;

    /**
     * The roles already under {@code /aws-service-role/} when this class started. A QuarkusTest
     * shares one application with every other test class, so these belong to someone else and
     * the teardown has to leave them where they are. Taken on the first test rather than in
     * {@code @BeforeAll}, where the injected URI is still null and nothing can reach the
     * application yet.
     */
    private static List<String> rolesAlreadyThere;

    /**
     * Quarkus points RestAssured at the application for the duration of a test method and
     * resets the port afterwards, so teardown has to say where the application is. The test
     * port is random ({@code quarkus.http.test-port: 0}), which leaves the injected URI as
     * the only place to read it from.
     */
    @TestHTTPResource("/")
    static URI baseUri;

    @BeforeEach
    void notePreExistingServiceRoles() {
        if (rolesAlreadyThere == null) {
            rolesAlreadyThere = rolesUnderTheServiceRolePath();
        }
    }

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
            // The table carries AWS's recorded name, RDS rather than the derived Rds.
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForRDS"));

        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "rds.application-autoscaling.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // No recording covers this dimension, so it keeps the derived name. The point of
            // the case is that the two principals stay distinct, which holds either way.
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

        // The role is parked at the account root, so the teardown's sweep of the service-role
        // path never sees it and it has to go from here. Expecting 200 is also the assertion
        // that the rejected call above left it alone, the way the sibling test reaches for
        // GetRole: this action answers NoSuchEntity for a role that is already gone.
        given()
            .formParam("Action", "DeleteRole")
            .formParam("RoleName", "NotServiceLinked")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
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
        RequestSpecification request = given().header("Authorization", AUTH_HEADER).formParam("Action", action);
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
            .formParam("InstanceProfileName", PROTECTION_PROBE_PROFILE)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        protectionProfileCreated = true;

        refusedAsUnmodifiable("AddRoleToInstanceProfile",
                "InstanceProfileName", PROTECTION_PROBE_PROFILE, "RoleName", roleName);
        refusedAsUnmodifiable("RemoveRoleFromInstanceProfile",
                "InstanceProfileName", PROTECTION_PROBE_PROFILE, "RoleName", roleName);

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
    @Order(31)
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

    /**
     * Most services refuse a {@code CustomSuffix}. AWS answers {@code InvalidInput} "Custom suffix
     * is not allowed for &lt;service&gt;", recorded under {@code @markers.aws.validated} for 68 of
     * the 71 service principals LocalStack exercises.
     */
    @Test
    @Order(26)
    void aCustomSuffixIsRefusedByAServiceRecordedAsRefusingOne() {
        for (String service : List.of("ecs.amazonaws.com", "rds.amazonaws.com",
                "elasticloadbalancing.amazonaws.com")) {
            given()
                .formParam("Action", "CreateServiceLinkedRole")
                .formParam("AWSServiceName", service)
                .formParam("CustomSuffix", "debug")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message",
                        equalTo("Custom suffix is not allowed for " + service));
        }
    }

    /**
     * And the three that do take one still do, so the check is a denylist of what AWS was recorded
     * refusing rather than a blanket refusal.
     */
    @Test
    @Order(27)
    void theServicesRecordedAsTakingASuffixStillTakeOne() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "connect.amazonaws.com")
            .formParam("CustomSuffix", "allowed")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForAmazonConnect_allowed"));
    }

    /**
     * A service the recordings do not cover keeps taking a suffix. This pins the decision rather
     * than the behaviour: refusing an unrecorded service would be inventing AWS behaviour, and
     * {@code es} is not in the recorded set at all, so it stays permitted.
     */
    @Test
    @Order(28)
    void aServiceTheRecordingsDoNotCoverStillTakesASuffix() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "unrecordedprobe.amazonaws.com")
            .formParam("CustomSuffix", "kept")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForUnrecordedprobe_kept"));
    }

    /**
     * Creating the same service-linked role twice is {@code InvalidInput} naming the role, not the
     * parameter: "Service role name AWSServiceRoleForBatch has been taken in this account, please
     * try a different suffix." Recorded against AWS for a second plain create of
     * {@code batch.amazonaws.com}.
     */
    @Test
    @Order(29)
    void creatingTheSameRoleTwiceNamesTheRoleThatIsTaken() {
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "takenprobe.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200);

        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "takenprobe.amazonaws.com")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
            .body("ErrorResponse.Error.Message",
                    equalTo("Service role name AWSServiceRoleForTakenprobe has been taken in this "
                            + "account, please try a different suffix."));
    }

    /**
     * A table name plus a suffix can breach the 64-character RoleName limit even though the name
     * alone fits. Two entries are close enough for that to matter, and neither has a recording
     * refusing a suffix, so the limit is the only thing standing between a caller and a role AWS
     * could not name.
     */
    @Test
    @Order(30)
    void aSuffixThatPushesATableNamePastTheRoleNameLimitIsRejected() {
        // AWSServiceRoleForApplicationAutoScaling_SageMakerEndpoint is 57 characters, so a
        // seven-character suffix is one too many.
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "sagemaker.application-autoscaling.amazonaws.com")
            .formParam("CustomSuffix", "sevench")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
            .body("ErrorResponse.Error.Message", containsString("exceeds the 64-character"));

        // Six fits, and the name is the table's rather than the derived one.
        given()
            .formParam("Action", "CreateServiceLinkedRole")
            .formParam("AWSServiceName", "sagemaker.application-autoscaling.amazonaws.com")
            .formParam("CustomSuffix", "sixchr")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName",
                    equalTo("AWSServiceRoleForApplicationAutoScaling_SageMakerEndpoint_sixchr"));
    }

    /** The class spells its requests out in full; the teardown needs one line, so it gets this. */
    private static RequestSpecification iam(String action) {
        return given().port(baseUri.getPort())
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", action);
    }

    private static List<String> rolesUnderTheServiceRolePath() {
        return iam("ListRoles")
            .formParam("PathPrefix", SERVICE_LINKED_ROLE_PATH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getList("ListRolesResponse.ListRolesResult.Roles.member.RoleName", String.class);
    }

    /** What is under the path now, minus what was already there when this class started. */
    private static List<String> rolesThisClassAdded() {
        List<String> added = new ArrayList<>(rolesUnderTheServiceRolePath());
        added.removeAll(rolesAlreadyThere);
        return added;
    }

    /**
     * Deletes everything this class creates: the roles it left under {@code /aws-service-role/}
     * and the instance profile the protection test needs. The tests here make those as fixtures
     * and mostly have no reason to remove them, and a QuarkusTest shares one application across
     * classes, so without this the leftovers outlive the class and are visible to anything that
     * lists roles or instance profiles unscoped.
     *
     * <p>Once at the end rather than after each test, on purpose: orders 1 to 8 are a single
     * narrative where one test's role is the next one's fixture, and the delete in order 6 is
     * itself under test. Cleaning between tests would take that apart.
     *
     * <p>Which roles are this class's own is decided by comparing the path against the snapshot
     * taken before the first test, not by a list of the principals used here: a list goes stale
     * the moment a test is added, and sweeping the whole path takes roles that other classes
     * made and a later class can still be holding. The profile goes by name, since other classes
     * keep their own profiles at the same path.
     */
    @AfterAll
    static void cleanup() {
        if (rolesAlreadyThere == null) {
            // No test of this class got as far as the snapshot, so it created nothing and there
            // is no record of what was already here. Sweeping without that record would take
            // other classes' roles, and failing here would bury whatever stopped the snapshot.
            return;
        }

        // A role created here and then found in the listing is what keeps the closing assertion
        // honest. If the listing ever stopped returning anything for this path, the sweep below
        // would delete nothing and the empty result would still read as clean.
        String sentinel = iam("CreateServiceLinkedRole")
            .formParam("AWSServiceName", "cleanupprobe.amazonaws.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getString("CreateServiceLinkedRoleResponse.CreateServiceLinkedRoleResult.Role.RoleName");

        try {
            List<String> leftOver = rolesThisClassAdded();
            assertTrue(leftOver.contains(sentinel),
                    "the path listing should see the role just created, found: " + leftOver);

            for (String roleName : leftOver) {
                // Two kinds live under this path. Most are service-linked and only
                // DeleteServiceLinkedRole will take them. A couple are ordinary roles the protection
                // tests park there with CreateRole, and that action answers NoSuchEntity for those,
                // because the mark comes from the action that minted the role, not from its path.
                // Any other status leaves the role in place for the closing assertion to name.
                int status = iam("DeleteServiceLinkedRole")
                    .formParam("RoleName", roleName)
                .when()
                    .post("/")
                .then()
                    .extract().statusCode();
                if (status == 404) {
                    iam("DeleteRole")
                        .formParam("RoleName", roleName)
                    .when()
                        .post("/")
                    .then()
                        .statusCode(200);
                }
            }

            List<String> remaining = rolesThisClassAdded();
            assertTrue(remaining.isEmpty(),
                    "the class should leave no service-linked role behind, found: " + remaining);

            if (protectionProfileCreated) {
                // Expecting 200 is also the assertion that the profile was there to clean: this
                // action answers NoSuchEntity for one that is already gone.
                iam("DeleteInstanceProfile")
                    .formParam("InstanceProfileName", PROTECTION_PROBE_PROFILE)
                .when()
                    .post("/")
                .then()
                    .statusCode(200);
            }
        } finally {
            // The sweep above normally takes the probe with it, so a 404 here is the ordinary
            // outcome. Nothing is asserted, both because there is nothing left to learn and
            // because a throw here would bury whatever failed above.
            iam("DeleteServiceLinkedRole")
                .formParam("RoleName", sentinel)
            .when()
                .post("/");
        }
    }
}
