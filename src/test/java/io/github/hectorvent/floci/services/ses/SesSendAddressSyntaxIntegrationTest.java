package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SesSendAddressSyntaxIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String TO = "success@simulator.amazonses.com";

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
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {
                    "FromEmailAddress": "sender@example.com",
                    "DefaultContent": {"Template": {
                        "TemplateContent": {"Subject": "s", "Text": "b"},
                        "TemplateData": "{}"}},
                    "BulkEmailEntries": [
                        {"Destination": {"ToAddresses": ["%1$s"]}},
                        {"Destination": {"ToAddresses": ["success.simulator.amazonses.com"]}},
                        {"Destination": {"ToAddresses": ["%1$s"], "CcAddresses": ["やまだ@example.com"]}}
                    ]
                }
                """.formatted(TO))
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
            .formParam("Source", "sender@example.com")
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
}
