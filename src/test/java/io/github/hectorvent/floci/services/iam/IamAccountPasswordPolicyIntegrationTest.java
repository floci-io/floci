package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the IAM account password policy via the Query Protocol, covering the full
 * HTTP stack through {@link AwsQueryController} → {@link IamQueryHandler}.
 *
 * <p>Ordered: the policy is a single per-account value, so these cases share state deliberately —
 * missing before any update, get-after-update, wholesale replace, range rejection, delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamAccountPasswordPolicyIntegrationTest {

    // The Query-protocol controller resolves the target service from the credential scope,
    // so every IAM call carries one.
    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    @Test
    @Order(1)
    void getBeforeAnyUpdateIsNoSuchEntity() {
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
    void deleteBeforeAnyUpdateIsNoSuchEntity() {
        given()
            .formParam("Action", "DeleteAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(3)
    void updateSetsThePolicy() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "12")
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
    @Order(4)
    void getReturnsThePolicyJustSet() {
        given()
            .formParam("Action", "GetAccountPasswordPolicy")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "MinimumPasswordLength", equalTo("12"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "RequireSymbols", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "RequireNumbers", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "RequireUppercaseCharacters", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "RequireLowercaseCharacters", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "AllowUsersToChangePassword", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "ExpirePasswords", equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "MaxPasswordAge", equalTo("90"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "PasswordReusePrevention", equalTo("5"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "HardExpiry", equalTo("true"));
    }

    @Test
    @Order(5)
    void updateReplacesTheEntirePolicyRatherThanMerging() {
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
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "MinimumPasswordLength", equalTo("8"))
            // RequireSymbols was true from the previous update; an omitted field resets rather
            // than carries over.
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "RequireSymbols", equalTo("false"))
            // MaxPasswordAge was set previously; omitted here it must not be echoed back at all.
            .body(not(containsString("MaxPasswordAge")))
            // HardExpiry was true from the previous update; omitted here it must reset to its
            // AWS-documented default of false, not be echoed back as true nor omitted entirely.
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "HardExpiry", equalTo("false"));
    }

    @Test
    @Order(6)
    void updateWithMinimumLengthOutOfRangeIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "129")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMaxPasswordAgeOutOfRangeIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MaxPasswordAge", "1096")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithPasswordReusePreventionOutOfRangeIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("PasswordReusePrevention", "25")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMalformedRequireSymbolsIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("RequireSymbols", "banana")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMalformedHardExpiryIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("HardExpiry", "banana")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMalformedMinimumPasswordLengthIsRejected() {
        // A non-numeric MinimumPasswordLength must be rejected outright, not silently coerced
        // to the field's default (6) — that would let a caller's typo succeed with 200 while
        // quietly ignoring the value they asked for.
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "abc")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMalformedMaxPasswordAgeIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MaxPasswordAge", "abc")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithMalformedPasswordReusePreventionIsRejected() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("PasswordReusePrevention", "abc")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(6)
    void updateWithHardExpiryOmittedDefaultsToFalseInResponse() {
        // HardExpiry has no "optional/absent" state on the wire — AWS documents it as a boolean
        // that always defaults to false, unlike MaxPasswordAge/PasswordReusePrevention which stay
        // absent when unset.
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "10")
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
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy."
                    + "HardExpiry", equalTo("false"));
    }

    @Test
    @Order(7)
    void deleteRemovesThePolicy() {
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
}
