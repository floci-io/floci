package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SES via the query (form-encoded) protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIntegrationTest {

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/" + service + "/aws4_request";
    }

    @Test
    @Order(1)
    void verifyEmailIdentity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", "sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("VerifyEmailIdentityResponse"))
            .body(containsString("VerifyEmailIdentityResult"));
    }

    @Test
    @Order(2)
    void verifyEmailIdentity_second() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", "recipient@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void verifyDomainIdentity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "VerifyDomainIdentity")
            .formParam("Domain", "example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<VerificationToken>"));
    }

    @Test
    @Order(4)
    void listIdentities() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "ListIdentities")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sender@example.com"))
            .body(containsString("recipient@example.com"))
            .body(containsString("example.com"));
    }

    @Test
    @Order(5)
    void listIdentities_filteredByType() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "ListIdentities")
            .formParam("IdentityType", "Domain")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("example.com"))
            .body(not(containsString("sender@example.com")));
    }

    @Test
    @Order(6)
    void getIdentityVerificationAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "GetIdentityVerificationAttributes")
            .formParam("Identities.member.1", "sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sender@example.com"))
            .body(containsString("<VerificationStatus>Success</VerificationStatus>"));
    }

    @Test
    @Order(7)
    void getIdentityVerificationAttributes_unknownIdentity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "GetIdentityVerificationAttributes")
            .formParam("Identities.member.1", "unknown@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<VerificationStatus>NotStarted</VerificationStatus>"));
    }

    @Test
    @Order(8)
    void listVerifiedEmailAddresses() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "ListVerifiedEmailAddresses")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sender@example.com"))
            .body(containsString("recipient@example.com"));
    }

    @Test
    @Order(9)
    void sendEmail() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Message.Subject.Data", "Test Subject")
            .formParam("Message.Body.Text.Data", "Hello from SES!")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(10)
    void sendEmail_withHtmlBody() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "SendEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "recipient@example.com")
            .formParam("Message.Subject.Data", "HTML Test")
            .formParam("Message.Body.Html.Data", "<h1>Hello</h1>")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(11)
    void sendRawEmail() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "SendRawEmail")
            .formParam("Source", "sender@example.com")
            .formParam("Destinations.member.1", "recipient@example.com")
            .formParam("RawMessage.Data", "Subject: Test\r\n\r\nRaw body")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(12)
    void getSendQuota() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "GetSendQuota")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Max24HourSend>"))
            .body(containsString("<MaxSendRate>"))
            .body(containsString("<SentLast24Hours>"));
    }

    @Test
    @Order(13)
    void getSendStatistics() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "GetSendStatistics")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SendDataPoints>"))
            .body(containsString("<DeliveryAttempts>"));
    }

    @Test
    @Order(14)
    void getAccountSendingEnabled() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "GetAccountSendingEnabled")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Enabled>true</Enabled>"));
    }

    @Test
    @Order(14)
    void getAccountSendingEnabled_acceptsSesv2CredentialScopeAlias() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("sesv2"))
            .formParam("Action", "GetAccountSendingEnabled")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Enabled>true</Enabled>"));
    }

    @Test
    @Order(15)
    void getIdentityDkimAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "GetIdentityDkimAttributes")
            .formParam("Identities.member.1", "example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("example.com"))
            .body(containsString("<DkimEnabled>"));
    }

    @Test
    @Order(16)
    void setIdentityNotificationTopic() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "SetIdentityNotificationTopic")
            .formParam("Identity", "sender@example.com")
            .formParam("NotificationType", "Bounce")
            .formParam("SnsTopic", "arn:aws:sns:us-east-1:000000000000:bounce-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SetIdentityNotificationTopicResult"));
    }

    @Test
    @Order(17)
    void getIdentityNotificationAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "GetIdentityNotificationAttributes")
            .formParam("Identities.member.1", "sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sender@example.com"))
            .body(containsString("bounce-topic"));
    }

    @Test
    @Order(18)
    void deleteVerifiedEmailAddress() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "DeleteVerifiedEmailAddress")
            .formParam("EmailAddress", "recipient@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "ListVerifiedEmailAddresses")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("recipient@example.com")));
    }

    @Test
    @Order(19)
    void deleteIdentity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "DeleteIdentity")
            .formParam("Identity", "sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteIdentityResult"));

        // Verify it's gone
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "ListIdentities")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("sender@example.com")));
    }

    @Test
    @Order(20)
    void deleteDomainIdentity() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "DeleteIdentity")
            .formParam("Identity", "example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteIdentityResult"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", authorization("email"))
            .formParam("Action", "ListIdentities")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("example.com")));
    }

    @Test
    @Order(21)
    void unsupportedAction_returns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
            .formParam("Action", "UnknownSesAction")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("UnsupportedOperation"));
    }
}
