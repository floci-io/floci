package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.account.AlternateContactRequests.alternateContactRequest;
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
        post("/getAlternateContact", "{\"AlternateContactType\":\"OTHER\"}")
                .statusCode(400).body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Invalid parameter at 'AlternateContactType' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [BILLING, OPERATIONS, SECURITY]'"));
    }

    @Test
    void rejectsNonStringMember() {
        ObjectNode request = alternateContactRequest().put("PhoneNumber", 12025550123L);
        post("/putAlternateContact", request.toString())
                .statusCode(400).body("__type", equalTo("SerializationException"))
                .body("message", equalTo("PhoneNumber must be a string."));
    }

    @Test
    void rejectsNonStringAccountIdInsteadOfFallingBackToCaller() {
        ObjectNode request = alternateContactRequest().put("AccountId", 123456789012L);
        post("/putAlternateContact", request.toString())
                .statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void trailingJsonReturnsSerializationException() {
        post("/getAlternateContact", "{\"AlternateContactType\":\"SECURITY\"} {}")
                .statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void putThenDeleteThenGetReturnsResourceNotFound() {
        put("security@example.com").statusCode(200);
        delete().statusCode(200).body(equalTo(""));
        get().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteUnsetContactReturnsResourceNotFound() {
        delete().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    private static ValidatableResponse put(String email) {
        return post("/putAlternateContact", alternateContactRequest().put("EmailAddress", email).toString());
    }

    private static ValidatableResponse get() {
        return post("/getAlternateContact", "{\"AlternateContactType\":\"SECURITY\"}");
    }

    private static ValidatableResponse delete() {
        return post("/deleteAlternateContact", "{\"AlternateContactType\":\"SECURITY\"}");
    }

    private static ValidatableResponse post(String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body(body).post(path).then();
    }
}
