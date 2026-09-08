package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AccountAlternateContactIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/account/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void createsUpdatesReadsAndDeletesAlternateContact() {
        put("security@example.com").statusCode(200);
        get().statusCode(200).body("AlternateContact.EmailAddress", equalTo("security@example.com"));
        put("security-updated@example.com").statusCode(200);
        get().statusCode(200).body("AlternateContact.EmailAddress", equalTo("security-updated@example.com"));
    }

    @Test
    void rejectsInvalidContactType() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"AlternateContactType\":\"OTHER\"}")
                .post("/getAlternateContact").then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void rejectsNonStringAccountIdInsteadOfFallingBackToCaller() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"AccountId\":123456789012,\"AlternateContactType\":\"SECURITY\",\"Name\":\"Security\",\"Title\":\"Owner\",\"EmailAddress\":\"security@example.com\",\"PhoneNumber\":\"+12025550123\"}")
                .post("/putAlternateContact").then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void trailingJsonReturnsSerializationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"AlternateContactType\":\"SECURITY\"} {}")
                .post("/getAlternateContact").then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    private static io.restassured.response.ValidatableResponse put(String email) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"AlternateContactType\":\"SECURITY\",\"Name\":\"Security\",\"Title\":\"Owner\",\"EmailAddress\":\"" + email + "\",\"PhoneNumber\":\"+12025550123\"}")
                .post("/putAlternateContact").then();
    }

    private static io.restassured.response.ValidatableResponse get() {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"AlternateContactType\":\"SECURITY\"}")
                .post("/getAlternateContact").then();
    }
}
