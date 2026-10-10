package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SesSendAddressLengthIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String DOMAIN = "example.com";
    private static final String TOO_LONG = "a".repeat(321 - DOMAIN.length() - 1) + "@" + DOMAIN;
    private static final String EXPECTED_MESSAGE =
            "Address length is more than 320 characters long: '" + TOO_LONG + "'.";

    @Test
    void v1SendEmail_sourceOverLimit_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", TOO_LONG)
            .formParam("Destination.ToAddresses.member.1", "success@simulator.amazonses.com")
            .formParam("Message.Subject.Data", "Subject")
            .formParam("Message.Body.Text.Data", "Body")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(EXPECTED_MESSAGE));
    }

    @Test
    void v2SendEmail_fromOverLimit_returnsBadRequestException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["success@simulator.amazonses.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(TOO_LONG))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(EXPECTED_MESSAGE));
    }

    @Test
    void v1SendRawEmail_destinationOverLimit_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", TOO_LONG)
            .formParam("RawMessage.Data", Base64.getEncoder().encodeToString(
                    "From: sender@example.com\r\nSubject: s\r\n\r\nb".getBytes(StandardCharsets.UTF_8)))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(EXPECTED_MESSAGE));
    }

    @Test
    void v2SendEmail_feedbackForwardingOverLimit_returnsBadRequestException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "FeedbackForwardingEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["success@simulator.amazonses.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(TOO_LONG))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(EXPECTED_MESSAGE));
    }

    @Test
    void v2SendEmail_rawWithCcOverLimit_returnsBadRequestException() {
        String raw = Base64.getEncoder().encodeToString(
                "From: sender@example.com\r\nTo: success@simulator.amazonses.com\r\nSubject: s\r\n\r\nb"
                        .getBytes(StandardCharsets.UTF_8));
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "Destination": {"ToAddresses": ["success@simulator.amazonses.com"], "CcAddresses": ["%s"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(TOO_LONG, raw))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(EXPECTED_MESSAGE));
    }

    @Test
    void v2SendBulkEmail_fromOverLimit_returnsInvalidEmailAddress() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "DefaultContent": {"Template": {
                        "TemplateContent": {"Subject": "s", "Text": "b"},
                        "TemplateData": "{}"}},
                    "BulkEmailEntries": [{"Destination": {"ToAddresses": ["success@simulator.amazonses.com"]}}]
                }
                """.formatted(TOO_LONG))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid email address<" + TOO_LONG + ">."));
    }

    @Test
    void v1SendBulkTemplatedEmail_sourceOverLimit_returnsInvalidEmailAddress() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", TOO_LONG)
            .formParam("Template", "address-length-missing")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "success@simulator.amazonses.com")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Invalid email address<" + TOO_LONG + ">."));
    }
}
