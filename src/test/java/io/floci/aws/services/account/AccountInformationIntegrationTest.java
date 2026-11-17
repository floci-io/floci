package io.floci.aws.services.account;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.services.account.AccountNameRequests.accountNameRequest;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Accounts in no organization; {@code AccountOrganizationsContextIntegrationTest} covers the others. */
@QuarkusTest
class AccountInformationIntegrationTest {
    private static final AtomicInteger ACCOUNTS = new AtomicInteger();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void accountIsActiveAndUnnamedUntilPutAccountName() {
        String account = newAccount();

        get(account, "{}").statusCode(200)
                .body("AccountId", equalTo(account))
                .body("AccountState", equalTo("ACTIVE"))
                .body("$", not(hasKey("AccountName")))
                .body("$", not(hasKey("AccountCreatedDate")));

        put(account, accountNameRequest("Dev Sandbox").toString()).statusCode(200).body(equalTo(""));

        get(account, "{}").statusCode(200)
                .body("AccountId", equalTo(account))
                .body("AccountName", equalTo("Dev Sandbox"))
                .body("AccountState", equalTo("ACTIVE"));
        get(newAccount(), "{}").statusCode(200).body("$", not(hasKey("AccountName")));
    }

    @Test
    void putAccountNameRejectsANameOutsideTheModel() {
        String account = newAccount();
        put(account, accountNameRequest("Dev Sandbox").toString()).statusCode(200);

        put(account, accountNameRequest("<Dev Sandbox>").toString()).statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", startsWith("Invalid parameter at 'AccountName'"));
        put(account, "{}").statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", startsWith("Invalid parameter at 'AccountName'"));
        put(account, "{\"AccountName\":42}").statusCode(400).body("__type", equalTo("SerializationException"));

        get(account, "{}").statusCode(200).body("AccountName", equalTo("Dev Sandbox"));
    }

    @Test
    void anotherAccountIsRefusedOutsideAnOrganization() {
        String account = newAccount();

        put(account, accountNameRequest("111122223333", "Member").toString())
                .statusCode(403).body("__type", equalTo("AccessDeniedException"));
        get(account, "{\"AccountId\":\"111122223333\"}")
                .statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    /** Names cannot be deleted, so every test acts on an account of its own. */
    private static String newAccount() {
        return String.format("7310009000%02d", ACCOUNTS.incrementAndGet());
    }

    private static ValidatableResponse put(String account, String body) {
        return post(account, "/putAccountName", body);
    }

    private static ValidatableResponse get(String account, String body) {
        return post(account, "/getAccountInformation", body);
    }

    private static ValidatableResponse post(String account, String path, String body) {
        return given().contentType("application/json")
                .header("Authorization", EnforcementFixtures.auth(account, "account"))
                .body(body).post(path).then();
    }
}
