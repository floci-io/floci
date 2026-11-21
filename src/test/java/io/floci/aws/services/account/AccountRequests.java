package io.floci.aws.services.account;

import io.floci.aws.testing.EnforcementFixtures;
import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

/** An Account Management request sent by a given caller account, for the integration tests. */
final class AccountRequests {

    private AccountRequests() {
    }

    static ValidatableResponse account(String caller, String path, String body) {
        return given().contentType("application/json")
                .header("Authorization", EnforcementFixtures.auth(caller, "account"))
                .body(body).post(path).then();
    }
}
