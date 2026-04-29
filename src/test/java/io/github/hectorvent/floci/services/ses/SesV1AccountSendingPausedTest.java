package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that v1 Query SES send actions reject requests when account-level
 * sending is disabled, matching v2 REST JSON behavior and AWS semantics.
 */
@QuarkusTest
class SesV1AccountSendingPausedTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @BeforeEach
    void disableSending() {
        given()
            .contentType("application/json")
            .body("{\"SendingEnabled\":false}")
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);
    }

    @AfterEach
    void restoreSending() {
        given()
            .contentType("application/json")
            .body("{\"SendingEnabled\":true}")
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);
    }

    @Test
    void sendEmail_paused_returnsAccountSendingPausedException() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Message.Subject.Data", "Subject")
            .formParam("Message.Body.Text.Data", "Body")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"));
    }

    @Test
    void sendRawEmail_paused_returnsAccountSendingPausedException() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", "recipient@example.com")
            .formParam("RawMessage.Data", "Subject: Hello\r\n\r\nBody")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"));
    }

    @Test
    void sendTemplatedEmail_paused_returnsAccountSendingPausedException() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Template", "any-template")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"));
    }
}
