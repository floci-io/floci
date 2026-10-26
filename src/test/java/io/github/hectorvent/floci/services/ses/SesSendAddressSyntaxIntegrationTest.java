package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SesSendAddressSyntaxIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String TO = "success@simulator.amazonses.com";
    // A sender of its own: an identity under example.com would outlive this class and reach the
    // other SES classes that share the application.
    private static final String VERIFIED_SENDER = "syntax-sender@floci.test";

    private static void verifySender() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", VERIFIED_SENDER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void v1SendEmail_sourceWithoutAt_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender.example.com")
            .formParam("Destination.ToAddresses.member.1", TO)
            .formParam("Message.Subject.Data", "Subject")
            .formParam("Message.Body.Text.Data", "Body")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Missing final '@domain'"));
    }

    @Test
    void v1SendRawEmail_nonAsciiDomainDestination_returnsInvalidParameterValue() {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", "success@例え.jp")
            .formParam("RawMessage.Data", Base64.getEncoder().encodeToString(
                    "From: sender@example.com\r\nSubject: s\r\n\r\nb".getBytes(StandardCharsets.UTF_8)))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Domain contains control or whitespace"));
    }

    @Test
    void v2SendEmail_nonAsciiLocalRecipient_returnsBadRequestException() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "Destination": {"ToAddresses": ["やまだ@simulator.amazonses.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Local address contains control or whitespace"));
    }

    @Test
    void v2SendBulkEmail_fromWithEmptyDomain_returnsInvalidEmailAddress() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@",
                    "DefaultContent": {"Template": {
                        "TemplateContent": {"Subject": "s", "Text": "b"},
                        "TemplateData": "{}"}},
                    "BulkEmailEntries": [{"Destination": {"ToAddresses": ["%s"]}}]
                }
                """.formatted(TO))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid email address<sender@>."));
    }

    @Test
    void v2SendBulkEmail_malformedEntryRecipient_failsOnlyThatEntry() {
        verifySender();
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%2$s",
                    "DefaultContent": {"Template": {
                        "TemplateContent": {"Subject": "s", "Text": "b"},
                        "TemplateData": "{}"}},
                    "BulkEmailEntries": [
                        {"Destination": {"ToAddresses": ["%1$s"]}},
                        {"Destination": {"ToAddresses": ["success.simulator.amazonses.com"]}},
                        {"Destination": {"ToAddresses": ["%1$s"], "CcAddresses": ["やまだ@example.com"]}}
                    ]
                }
                """.formatted(TO, VERIFIED_SENDER))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"))
            .body("BulkEmailEntryResults[1].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[1].Error", equalTo("Missing final '@domain'"))
            .body("BulkEmailEntryResults[1].MessageId", nullValue())
            .body("BulkEmailEntryResults[2].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[2].Error", equalTo("Local address contains control or whitespace"));
    }

    @Test
    void v1SendBulkTemplatedEmail_malformedEntryRecipient_failsOnlyThatEntry() {
        verifySender();
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "address-syntax-bulk")
            .formParam("Template.SubjectPart", "s")
            .formParam("Template.TextPart", "b")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", VERIFIED_SENDER)
            .formParam("Template", "address-syntax-bulk")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "success@")
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", TO)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[0].Status",
                    equalTo("InvalidParameterValue"))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[0].Error",
                    equalTo("Missing domain"))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[1].Status",
                    equalTo("Success"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteTemplate")
            .formParam("TemplateName", "address-syntax-bulk")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void v1SendBulkTemplatedEmail_malformedSourceIsReportedBeforeMissingTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "Alice <sender@>")
            .formParam("Template", "address-syntax-missing")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", TO)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Invalid email address<Alice <sender@>>."));
    }

    @Test
    void v2SendBulkEmail_malformedFromIsReportedBeforeMissingTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender.example.com",
                    "DefaultContent": {"Template": {"TemplateName": "address-syntax-missing", "TemplateData": "{}"}},
                    "BulkEmailEntries": [{"Destination": {"ToAddresses": ["%s"]}}]
                }
                """.formatted(TO))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid email address<sender.example.com>."));
    }

    @Test
    void v2SendEmail_newlineInQuotedLocalPart_returnsInvalidEmailAddress() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "\\"a\\nb\\"@example.com",
                    "Destination": {"ToAddresses": ["%s"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(TO))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid email address \"a\nb\"@example.com."));
    }

    @Test
    void v2SendBulkEmail_malformedFeedbackForwarding_rejectsTheRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "FeedbackForwardingEmailAddress": "bounce.example.com",
                    "DefaultContent": {"Template": {
                        "TemplateContent": {"Subject": "s", "Text": "b"},
                        "TemplateData": "{}"}},
                    "BulkEmailEntries": [{"Destination": {"ToAddresses": ["%s"]}}]
                }
                """.formatted(TO))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid email address<bounce.example.com>."));
    }

    @Test
    void v1SendBulkTemplatedEmail_malformedReturnPath_rejectsTheRequest() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "sender@example.com")
            .formParam("ReturnPath", "bounce@")
            .formParam("Template", "address-syntax-missing")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", TO)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Invalid email address<bounce@>."));
    }

    @ParameterizedTest
    @CsvSource({
        "sender.example.com, Missing final '@domain'",
        "sender@, Invalid From address provided.",
        "やまだ@example.com, Invalid From address provided.",
        "sender@例え.jp, Invalid From address provided."
    })
    void v2SendEmailRaw_malformedFromHeaderWithoutFromEmailAddress(String from, String message) {
        given()
            .contentType("application/json; charset=UTF-8")
            .header("Authorization", AUTH)
            .body("""
                {
                    "Destination": {"ToAddresses": ["%s"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(TO, rawMessage(from)))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sender.example.com", "sender@", "やまだ@example.com", "sender@例え.jp"})
    void v2SendEmailRaw_malformedFromHeaderIsNotCheckedWithFromEmailAddress(String from) {
        verifySender();
        given()
            .contentType("application/json; charset=UTF-8")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["%s"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(VERIFIED_SENDER, TO, rawMessage(from)))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    void v2SendEmailRaw_overLimitFromHeaderIsNotMeasuredWithFromEmailAddress() {
        String from = "A".repeat(321 - " <sender@example.com>".length()) + " <sender@example.com>";
        verifySender();

        given()
            .contentType("application/json; charset=UTF-8")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["%s"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(VERIFIED_SENDER, TO, rawMessage(from)))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @ParameterizedTest
    @CsvSource({
        "sender.example.com, Missing required header 'To'.",
        "sender@, Invalid From address provided."
    })
    void v2SendEmailRaw_malformedFromHeaderWithoutAnyDestination(String from, String message) {
        String raw = Base64.getEncoder().encodeToString(("From: " + from + "\r\nSubject: s\r\n\r\nb")
                .getBytes(StandardCharsets.UTF_8));

        given()
            .contentType("application/json; charset=UTF-8")
            .header("Authorization", AUTH)
            .body("""
                {"Content": {"Raw": {"Data": "%s"}}}
                """.formatted(raw))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @ParameterizedTest
    @CsvSource({
        "sender.example.com, Missing final '@domain'",
        "sender@, Missing domain",
        "やまだ@example.com, Local address contains control or whitespace",
        "sender@例え.jp, Domain contains control or whitespace"
    })
    void v1SendRawEmail_malformedFromHeader_keepsJavaMailMessages(String from, String message) {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Destinations.member.1", TO)
            .formParam("RawMessage.Data", rawMessage(from))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "sender.example.com | User name is missing: 'sender.example.com'.",
        "Alice <sender.example.com> | User name is missing: 'Alice <sender.example.com>'.",
        "sender.example.com (Alice) | User name is missing: 'Alice <sender.example.com>'.",
        "\"sender example\" | User name is missing: '<\"sender example\">'.",
        "山田 <sender.example.com> | User name is missing: '=?UTF-8?B?5bGx55Sw?= <sender.example.com>'.",
        "\"Alice, B\" <sender.example.com> | User name is missing: '\"Alice, B\" <sender.example.com>'.",
        "sender@example.com <sender.example.com> | User name is missing: '\"sender@example.com\" <sender.example.com>'.",
        "Alice (Bob) <sender.example.com> | User name is missing: '\"Alice (Bob)\" <sender.example.com>'.",
        "Alice <sender.example.com> (Bob) | User name is missing: 'Alice <sender.example.com>'.",
        "\" \" <sender.example.com> (Alice) | User name is missing: 'sender.example.com'.",
        "<sender.example.com> (Alice) | User name is missing: 'Alice <sender.example.com>'.",
        "(Bob) <sender.example.com> | User name is missing: 'Bob <sender.example.com>'.",
        "(Bob) Alice <sender.example.com> | User name is missing: 'Alice <sender.example.com>'."
    })
    void v1SendRawEmail_fromHeaderWithoutAtAndSource_isAMissingUserName(String from, String message) {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", TO)
            .formParam("RawMessage.Data", rawMessage(from))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo(message));
    }

    @Test
    void v1SendRawEmail_otherFromHeaderViolationIsReportedBeforeMissingUserName() {
        given()
            .contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .header("Authorization", AUTH)
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", TO)
            .formParam("RawMessage.Data", rawMessage("sender.example.com, sender@"))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("Missing domain"));
    }

    private static String rawMessage(String from) {
        return Base64.getEncoder().encodeToString(("From: " + from + "\r\nTo: " + TO + "\r\nSubject: s\r\n\r\nb")
                .getBytes(StandardCharsets.UTF_8));
    }
}
