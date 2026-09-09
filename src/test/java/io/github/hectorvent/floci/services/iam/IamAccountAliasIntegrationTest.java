package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyOrNullString;

/**
 * Integration tests for IAM account aliases via the Query Protocol, covering the full HTTP stack
 * through {@link AwsQueryController} → {@link IamQueryHandler}.
 *
 * <p>Ordered: the alias is a single per-account value, so these cases share state deliberately —
 * empty list, create, read back, reject a second create, reject a mismatched delete, delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamAccountAliasIntegrationTest {

    private static final String ALIAS = "floci-test-alias";

    // The Query-protocol controller resolves the target service from the credential scope,
    // so every IAM call carries one.
    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    @Test
    @Order(1)
    void listAccountAliasesIsEmptyBeforeAnyCreate() {
        given()
            .formParam("Action", "ListAccountAliases")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.AccountAliases", isEmptyOrNullString())
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.IsTruncated", equalTo("false"));
    }

    @Test
    @Order(2)
    void createAccountAlias() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(3)
    void listAccountAliasesReturnsTheCreatedAlias() {
        given()
            .formParam("Action", "ListAccountAliases")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.AccountAliases.member", equalTo(ALIAS))
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.IsTruncated", equalTo("false"));
    }

    @Test
    @Order(4)
    void creatingAnotherAliasReplacesTheCurrentOne() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", "another-alias")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ListAccountAliases")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.AccountAliases.member",
                    equalTo("another-alias"));

        // Restore the alias the later ordered cases expect.
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void recreatingTheHeldAliasIsRejected() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    @Order(5)
    void deleteWithAMalformedAliasIsRejected() {
        given()
            .formParam("Action", "DeleteAccountAlias")
            .formParam("AccountAlias", "Bad_Alias")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(5)
    void deleteWithMismatchedAliasIsRejected() {
        given()
            .formParam("Action", "DeleteAccountAlias")
            .formParam("AccountAlias", "not-the-current-alias")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(6)
    void deleteAccountAlias() {
        given()
            .formParam("Action", "DeleteAccountAlias")
            .formParam("AccountAlias", ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ListAccountAliases")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.AccountAliases", isEmptyOrNullString());
    }

    @Test
    @Order(7)
    void deleteWhenNoAliasIsSetIsRejected() {
        given()
            .formParam("Action", "DeleteAccountAlias")
            .formParam("AccountAlias", ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-leading-hyphen", "trailing-hyphen-", "Upper-Case", "has_underscore"})
    @Order(8)
    void malformedAliasesAreRejected(String alias) {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", alias)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }
}
