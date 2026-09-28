package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for what {@code DeleteUser} requires removed first, and for what a user rename
 * carries along, via the Query Protocol through {@link AwsQueryController} →
 * {@link IamQueryHandler}.
 *
 * <p>Ordered: the cases share one user and walk it through each prerequisite in turn.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamDeleteUserPrerequisitesIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String USER_NAME = "delete-prereq-user";
    private static final String RENAMED_USER_NAME = "delete-prereq-renamed";
    private static final String GROUP_NAME = "delete-prereq-group";
    private static final String POLICY_NAME = "delete-prereq-inline";
    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:ListAllMyBuckets\","
                    + "\"Resource\":\"*\"}]}";

    private static String accessKeyId;

    private static ValidatableResponse call(String action, String... params) {
        if (params.length % 2 != 0) {
            throw new IllegalArgumentException("params must be name/value pairs, got " + params.length);
        }
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", IAM_CREDENTIAL);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static void expectDeleteConflict(String userName) {
        call("DeleteUser", "UserName", userName)
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));
    }

    @Test
    @Order(1)
    void createUserWithInlinePolicy() {
        call("CreateUser", "UserName", USER_NAME).statusCode(200);
        call("PutUserPolicy", "UserName", USER_NAME, "PolicyName", POLICY_NAME,
                "PolicyDocument", POLICY_DOCUMENT).statusCode(200);
    }

    @Test
    @Order(2)
    void deleteUserWithInlinePolicyIsDeleteConflict() {
        expectDeleteConflict(USER_NAME);
        call("GetUser", "UserName", USER_NAME).statusCode(200);
    }

    @Test
    @Order(3)
    void deleteUserWithAccessKeyIsDeleteConflict() {
        call("DeleteUserPolicy", "UserName", USER_NAME, "PolicyName", POLICY_NAME).statusCode(200);
        accessKeyId = call("CreateAccessKey", "UserName", USER_NAME)
            .statusCode(200)
            .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

        expectDeleteConflict(USER_NAME);
    }

    @Test
    @Order(4)
    void renameCarriesAccessKeysAndGroupMembership() {
        call("CreateGroup", "GroupName", GROUP_NAME).statusCode(200);
        call("AddUserToGroup", "GroupName", GROUP_NAME, "UserName", USER_NAME).statusCode(200);

        call("UpdateUser", "UserName", USER_NAME, "NewUserName", RENAMED_USER_NAME).statusCode(200);

        call("ListAccessKeys", "UserName", RENAMED_USER_NAME)
            .statusCode(200)
            .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.AccessKeyId",
                    equalTo(accessKeyId))
            .body("ListAccessKeysResponse.ListAccessKeysResult.AccessKeyMetadata.member.UserName",
                    equalTo(RENAMED_USER_NAME));
        call("GetGroup", "GroupName", GROUP_NAME)
            .statusCode(200)
            .body("GetGroupResponse.GetGroupResult.Users.member.UserName", equalTo(RENAMED_USER_NAME));
    }

    @Test
    @Order(5)
    void aNewUserWithTheOldNameDoesNotInheritTheKeys() {
        call("CreateUser", "UserName", USER_NAME).statusCode(200);
        call("ListAccessKeys", "UserName", USER_NAME)
            .statusCode(200)
            .body(not(containsString(accessKeyId)));
        call("DeleteUser", "UserName", USER_NAME).statusCode(200);
    }

    @Test
    @Order(6)
    void deleteSucceedsOnceEverythingIsRemoved() {
        call("DeleteAccessKey", "UserName", RENAMED_USER_NAME, "AccessKeyId", accessKeyId).statusCode(200);
        expectDeleteConflict(RENAMED_USER_NAME);

        call("RemoveUserFromGroup", "GroupName", GROUP_NAME, "UserName", RENAMED_USER_NAME).statusCode(200);
        call("DeleteGroup", "GroupName", GROUP_NAME).statusCode(200);
        call("DeleteUser", "UserName", RENAMED_USER_NAME).statusCode(200);
    }
}
