package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The send operations require a verified sender, as on AWS: "The message must be sent from a
 * verified email address or domain. If you attempt to send email using a non-verified address or
 * domain, the operation results in an "Email address not verified" error."
 * (<a href="https://docs.aws.amazon.com/ses/latest/APIReference/API_SendEmail.html">SendEmail</a>).
 * The sender counts as verified when the address itself or its domain is a verified identity.
 */
@QuarkusTest
class SesSendVerifiedSenderIntegrationTest {

    private static final String AUTH_V1 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String AUTH_V2 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String UNVERIFIED = "never-verified@unverified-sender.floci.test";
    private static final String VERIFIED = "verified@verified-sender.floci.test";
    private static final String NOT_VERIFIED_MESSAGE =
            "Email address is not verified. The following identities failed the check in region US-EAST-1: ";

    private static RequestSpecification query(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_V1)
                .formParam("Action", action);
    }

    private static void verifyEmailIdentity(String address) {
        query("VerifyEmailIdentity").formParam("EmailAddress", address)
            .when().post("/").then().statusCode(200);
    }

    private static String rawMessage(String from) {
        String raw = "From: " + from + "\r\nTo: to@example.com\r\nSubject: s\r\n\r\nbody";
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNothingCapturedFrom(String source) {
        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Source", everyItem(not(equalTo(source))));
    }

    @Test
    void v1SendEmail_unverifiedSource_returnsMessageRejected() {
        query("SendEmail")
            .formParam("Source", UNVERIFIED)
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Message.Subject.Data", "s")
            .formParam("Message.Body.Text.Data", "b")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v1SendEmail_displayNameSourceWithVerifiedAddress_succeeds() {
        verifyEmailIdentity(VERIFIED);

        query("SendEmail")
            .formParam("Source", "Verified Sender <" + VERIFIED + ">")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Message.Subject.Data", "s")
            .formParam("Message.Body.Text.Data", "b")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SendEmailResponse.SendEmailResult.MessageId", notNullValue());
    }

    @Test
    void v1SendRawEmail_unverifiedSource_returnsMessageRejected() {
        query("SendRawEmail")
            .formParam("Source", UNVERIFIED)
            .formParam("Destinations.member.1", "to@example.com")
            .formParam("RawMessage.Data", rawMessage(UNVERIFIED))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v1SendRawEmail_withoutSource_unverifiedMimeFrom_returnsMessageRejected() {
        query("SendRawEmail")
            .formParam("Destinations.member.1", "to@example.com")
            .formParam("RawMessage.Data", rawMessage(UNVERIFIED))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));
    }

    @Test
    void v1SendTemplatedEmail_unverifiedSource_returnsMessageRejected() {
        query("CreateTemplate")
            .formParam("Template.TemplateName", "verified-sender-check")
            .formParam("Template.SubjectPart", "s")
            .formParam("Template.TextPart", "b")
        .when()
            .post("/");

        query("SendTemplatedEmail")
            .formParam("Source", UNVERIFIED)
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Template", "verified-sender-check")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));
    }

    @Test
    void v2SendEmail_simple_unverifiedFrom_returnsMessageRejected() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_V2)
            .body("""
                {
                    "FromEmailAddress": "%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(UNVERIFIED))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));

        assertNothingCapturedFrom(UNVERIFIED);
    }

    @Test
    void v2SendEmail_raw_unverifiedMimeFrom_returnsMessageRejected() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_V2)
            .body("""
                {
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Raw": {"Data": "%s"}}
                }
                """.formatted(rawMessage(UNVERIFIED)))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", equalTo(NOT_VERIFIED_MESSAGE + UNVERIFIED));
    }

    @Test
    void v2SendEmail_verifiedEmailIdentity_succeeds() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_V2)
            .body("{\"EmailIdentity\": \"v2-" + VERIFIED + "\"}")
        .when()
            .post("/v2/email/identities");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_V2)
            .body("""
                {
                    "FromEmailAddress": "v2-%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(VERIFIED))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    void v2SendEmail_addressUnderVerifiedDomain_succeeds() {
        String domain = "verified-domain.floci.test";
        SesDomainIdentityTestHelper.createVerified(domain, AUTH_V2);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_V2)
            .body("""
                {
                    "FromEmailAddress": "anyone@%s",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
                }
                """.formatted(domain))
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    void v1SendEmail_addressUnderPendingDomain_returnsMessageRejected() {
        // A domain identity stays Pending until its DKIM records are published, and a pending
        // domain does not verify the addresses under it.
        String domain = "pending-domain.floci.test";
        query("VerifyDomainIdentity").formParam("Domain", domain)
            .when().post("/").then().statusCode(200);

        query("SendEmail")
            .formParam("Source", "anyone@" + domain)
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Message.Subject.Data", "s")
            .formParam("Message.Body.Text.Data", "b")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"))
            .body("ErrorResponse.Error.Message", equalTo(NOT_VERIFIED_MESSAGE + "anyone@" + domain));
    }
}
