package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.ses.SesV1Requests.query;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that v1 Query SES send actions reject requests when account-level
 * sending is disabled, matching v2 REST JSON behavior and AWS semantics.
 */
@QuarkusTest
class SesV1AccountSendingPausedTest {

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
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Message.Subject.Data", "Subject")
            .formParam("Message.Body.Text.Data", "Body")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"))
            .body(containsString("<Message>Sending is paused for this account.</Message>"));
    }

    @Test
    void sendRawEmail_paused_returnsAccountSendingPausedException() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", "recipient@example.com")
            .formParam("RawMessage.Data", "Subject: Hello\r\n\r\nBody")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"))
            .body(containsString("<Message>Sending is paused for this account.</Message>"));
    }

    @Test
    void sendTemplatedEmail_paused_returnsAccountSendingPausedException() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SesV1Requests.AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Template", "any-template")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"))
            .body(containsString("<Message>Sending is paused for this account.</Message>"));
    }

    @Test
    void sendBulkTemplatedEmail_pausedUnverifiedSender_isRejectedBeforeThePause() {
        sendBulkTemplatedEmail("paused-v1-unverified@floci.test")
            .statusCode(400)
            .body(containsString("<Code>MessageRejected</Code>"))
            .body(containsString("Email address is not verified."));
    }

    @Test
    void sendBulkTemplatedEmail_pausedVerifiedSender_returnsAccountSendingPausedException() {
        String sender = "paused-v1-verified@floci.test";
        query("VerifyEmailIdentity")
            .formParam("EmailAddress", sender)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        sendBulkTemplatedEmail(sender)
            .statusCode(400)
            .body(containsString("<Code>AccountSendingPausedException</Code>"))
            .body(containsString("<Message>Sending is paused for this account.</Message>"));
    }

    private static ValidatableResponse sendBulkTemplatedEmail(String sender) {
        return query("SendBulkTemplatedEmail")
            .formParam("Source", sender)
            .formParam("Template", "any-template")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "recipient@example.com")
        .when()
            .post("/")
        .then();
    }
}
