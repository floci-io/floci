package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for lex00/floci#74: {@code UpdateAccountPasswordPolicy} had no case in
 * {@link IamQueryHandler}'s action switch at all, so {@code aws_iam_account_password_policy}
 * failed on create with {@code UnsupportedOperation}. Covers the full HTTP stack through
 * {@link AwsQueryController} → {@link IamQueryHandler} → {@link IamService}, the same shape
 * {@link IamAccountAliasIntegrationTest} uses for the account alias.
 *
 * <p>Ordered: the password policy is a single per-account value, so these cases share state
 * deliberately — reject a read before any policy is set, set one, read it back, update it as
 * a whole-value replace (an omitted field reverts to its default rather than keeping the old
 * value), then delete it (idempotently) and confirm the read-back error returns.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamAccountPasswordPolicyIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    @Test
    @Order(1)
    void getBeforeAnyUpdateIsRejected() {
        given()
            .formParam("Action", "GetAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(2)
    void updateAccountPasswordPolicy() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "14")
            .formParam("RequireSymbols", "true")
            .formParam("RequireNumbers", "true")
            .formParam("RequireUppercaseCharacters", "true")
            .formParam("RequireLowercaseCharacters", "true")
            .formParam("AllowUsersToChangePassword", "true")
            .formParam("MaxPasswordAge", "90")
            .formParam("PasswordReusePrevention", "5")
            .formParam("HardExpiry", "true")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(3)
    void getAccountPasswordPolicyReturnsWhatWasSet() {
        given()
            .formParam("Action", "GetAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MinimumPasswordLength", equalTo("14"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireSymbols", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MaxPasswordAge", equalTo("90"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.PasswordReusePrevention", equalTo("5"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.HardExpiry", equalTo("true"))
            // Computed, not stored: true because MaxPasswordAge is set.
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.ExpirePasswords", equalTo("true"));
    }

    @Test
    @Order(4)
    void updateIsAWholeValueReplaceNotAPartialPatch() {
        // Omits every optional field this time - real IAM reverts each to its documented
        // default rather than keeping what Order(2) set, and MaxPasswordAge/
        // PasswordReusePrevention/HardExpiry go missing from the response entirely (they are
        // genuinely absent on the wire when unset, not rendered as 0/false).
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "8")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MinimumPasswordLength", equalTo("8"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireSymbols", equalTo("false"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.ExpirePasswords", equalTo("false"))
            // MaxPasswordAge is genuinely absent from the response (not an empty element) when
            // unset - XmlPath has no clean "child is absent" matcher for a leaf that was never
            // rendered at all, so assert on the raw body directly.
            .body(not(containsString("MaxPasswordAge")));
    }

    @Test
    @Order(5)
    void invalidMinimumPasswordLengthIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "200")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void deleteAccountPasswordPolicy() {
        given()
            .formParam("Action", "DeleteAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(7)
    void deleteIsIdempotentWhenNoPolicyIsSet() {
        given()
            .formParam("Action", "DeleteAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
