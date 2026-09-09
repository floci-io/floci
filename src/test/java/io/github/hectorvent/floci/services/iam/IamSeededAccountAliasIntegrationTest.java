package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that {@code floci.services.iam.account-alias} seeds the alias at startup, so callers
 * that read an account alias find one without creating it first.
 */
@QuarkusTest
@TestProfile(IamSeededAccountAliasIntegrationTest.SeededAliasProfile.class)
class IamSeededAccountAliasIntegrationTest {

    private static final String SEEDED_ALIAS = "seeded-alias";

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    public static class SeededAliasProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.account-alias", SEEDED_ALIAS);
        }
    }

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
                    equalTo(SEEDED_ALIAS));
    }

    @Test
    void recreatingTheSeededAliasIsRejected() {
        given()
            .formParam("Action", "CreateAccountAlias")
            .formParam("AccountAlias", SEEDED_ALIAS)
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
            .formParam("AccountAlias", SEEDED_ALIAS)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
