package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class IamCaseInsensitiveNamesIntegrationTest {

    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260920/us-east-1/iam/aws4_request";
    private static final String ACCOUNT_A_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260920/us-east-1/iam/aws4_request";
    private static final String ACCOUNT_B_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=222222222222/20260920/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";
    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    @Test
    void createActionsRejectNamesThatDifferOnlyByCase() {
        iam("CreateUser", Map.of("UserName", "CaseCollisionUser")).statusCode(200);
        assertAlreadyExists("CreateUser", Map.of("UserName", "casecollisionuser"));

        iam("CreateGroup", Map.of("GroupName", "CaseCollisionGroup")).statusCode(200);
        assertAlreadyExists("CreateGroup", Map.of("GroupName", "casecollisiongroup"));

        iam("CreateRole", Map.of(
                "RoleName", "CaseCollisionRole",
                "AssumeRolePolicyDocument", TRUST_POLICY)).statusCode(200);
        assertAlreadyExists("CreateRole", Map.of(
                "RoleName", "casecollisionrole",
                "AssumeRolePolicyDocument", TRUST_POLICY));

        iam("CreatePolicy", Map.of(
                "PolicyName", "CaseCollisionPolicy",
                "Path", "/first/",
                "PolicyDocument", POLICY_DOCUMENT)).statusCode(200);
        assertAlreadyExists("CreatePolicy", Map.of(
                "PolicyName", "casecollisionpolicy",
                "Path", "/second/",
                "PolicyDocument", POLICY_DOCUMENT));
    }

    @Test
    void caseInsensitiveUniquenessRemainsScopedToTheAccount() {
        Map<String, String> parameters = Map.of("UserName", "AccountScopedCaseName");

        iam(ACCOUNT_A_AUTHORIZATION, "CreateUser", parameters).statusCode(200);
        iam(ACCOUNT_B_AUTHORIZATION, "CreateUser", parameters).statusCode(200);
    }

    @Test
    void updateUserRejectsAnotherUserNameThatDiffersOnlyByCase() {
        iam("CreateUser", Map.of("UserName", "CaseRenameTarget")).statusCode(200);
        iam("CreateUser", Map.of("UserName", "CaseRenameSource")).statusCode(200);

        assertAlreadyExists("UpdateUser", Map.of(
                "UserName", "CaseRenameSource",
                "NewUserName", "CASERENAMETARGET"));

        iam("GetUser", Map.of("UserName", "CaseRenameSource"))
                .statusCode(200)
                .body("GetUserResponse.GetUserResult.User.UserName", equalTo("CaseRenameSource"));
    }

    private static void assertAlreadyExists(String action, Map<String, String> parameters) {
        iam(action, parameters)
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    private static ValidatableResponse iam(String action, Map<String, String> parameters) {
        return iam(AUTHORIZATION, action, parameters);
    }

    private static ValidatableResponse iam(String authorization, String action, Map<String, String> parameters) {
        return given()
                .formParam("Action", action)
                .formParams(parameters)
                .header("Authorization", authorization)
            .when()
                .post("/")
            .then();
    }
}
