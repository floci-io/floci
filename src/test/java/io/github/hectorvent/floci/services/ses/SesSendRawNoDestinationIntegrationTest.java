package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SesSendRawNoDestinationIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String TO = "success@simulator.amazonses.com";
    private static final String VERIFIED_SENDER = "no-destination-sender@floci.test";

    // The mailbox shows a raw message as RawData only, without the recipients Floci read from its
    // headers, so the stored record is checked directly.
    @Inject
    SesSentEmailService sentEmailService;

    private static String rawData(String headers) {
        return Base64.getEncoder().encodeToString(
                (headers + "Subject: s\r\n\r\nb").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void v2SendEmail_rawWithoutDestination_takesRecipientsFromHeaders() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", VERIFIED_SENDER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String messageId = given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(VERIFIED_SENDER, rawData("From: " + VERIFIED_SENDER + "\r\nTo: " + TO + "\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .extract().path("MessageId");

        assertThat(sentEmailService.find("us-east-1", messageId).orElseThrow().getToAddresses(),
                contains(TO));
    }

    @Test
    void v2SendEmail_rawWithoutAnyRecipient_returnsMissingToHeader() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Missing required header 'To'."));
    }

    @Test
    void v2SendEmail_rawWithoutAnyRecipient_reportsUnknownConfigurationSetFirst() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "ConfigurationSetName": "no-destination-missing-cs",
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Configuration set <no-destination-missing-cs> does not exist."));
    }

    @Test
    void v2SendEmail_rawWithEmptyDestination_returnsMissingToHeader() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Destination": {"ToAddresses": []},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Missing required header 'To'."));
    }

    @Test
    void v2SendEmail_rawWithNonArrayDestinationMember_returnsSerializationException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Destination": {"ToAddresses": "bad-shape"},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\nTo: " + TO + "\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    void v2SendEmail_rawWithoutDestination_checksHeaderRecipientSyntax() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\nTo: やまだ@simulator.amazonses.com\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Local address contains control or whitespace"));
    }

    @Test
    void v2SendEmail_rawWithoutDestination_checksCcHeaderRecipientSyntax() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawData("From: sender@example.com\r\nCc: やまだ@simulator.amazonses.com\r\n")))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Local address contains control or whitespace"));
    }

    @Test
    void v1SendRawEmail_withoutAnyRecipient_returnsMissingToHeader() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("RawMessage.Data", rawData("From: sender@example.com\r\n"))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Missing required header 'To'."));
    }
}
