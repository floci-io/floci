package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile.IAM_ACCOUNT_ALIAS;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that {@code floci.services.iam.account-alias} seeds the alias at startup, so callers
 * that read an account alias find one without creating it first.
 *
 * <p>The alias, {@link IndependentServiceSettingsProfile#IAM_ACCOUNT_ALIAS}, comes from
 * {@link IndependentServiceSettingsProfile}, which this class shares with other classes that each
 * call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class IamSeededAccountAliasIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    @Test
    void configuredAliasIsSeededAtStartup() {
        given()
            .formParam("Action", "ListAccountAliases")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAccountAliasesResponse.ListAccountAliasesResult.AccountAliases.member",
                    equalTo(IAM_ACCOUNT_ALIAS));
    }

    @Test
    void recreatingTheSeededAliasIsRejected() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", IAM_ACCOUNT_ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    /** A seeded alias is ordinary state, so a later create replaces it like any other. */
    @Test
    void creatingAnotherAliasReplacesTheSeededOne() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", "replacement-alias")
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
                    equalTo("replacement-alias"));

        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", IAM_ACCOUNT_ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
