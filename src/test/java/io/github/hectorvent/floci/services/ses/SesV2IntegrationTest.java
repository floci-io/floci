package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SES V2 via the REST JSON protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createEmailIdentity_email() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "v2sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("EMAIL_ADDRESS"))
            .body("VerifiedForSendingStatus", equalTo(true));
    }

    @Test
    @Order(2)
    void createEmailIdentity_domain() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "v2example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("DOMAIN"));
    }

    @Test
    @Order(3)
    void createEmailIdentity_missingField() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void listEmailIdentities() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("EmailIdentities", notNullValue())
            .body("EmailIdentities.size()", greaterThanOrEqualTo(2))
            .body("EmailIdentities.IdentityName", hasItem("v2sender@example.com"))
            .body("EmailIdentities.IdentityName", hasItem("v2example.com"));
    }

    @Test
    @Order(5)
    void getEmailIdentity() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("EMAIL_ADDRESS"))
            .body("VerifiedForSendingStatus", equalTo(true))
            .body("DkimAttributes", notNullValue());
    }

    @Test
    @Order(6)
    void getEmailIdentity_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/nonexistent@example.com")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void sendEmail_simple() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "v2sender@example.com",
                    "Destination": {
                        "ToAddresses": ["recipient@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "V2 Test Subject"},
                            "Body": {
                                "Text": {"Data": "Hello from SES V2!"}
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    @Order(8)
    void sendEmail_html() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "v2sender@example.com",
                    "Destination": {
                        "ToAddresses": ["recipient@example.com"],
                        "CcAddresses": ["cc@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "HTML V2 Test"},
                            "Body": {
                                "Html": {"Data": "<h1>Hello V2</h1>"}
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    @Order(9)
    void sendEmail_raw() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "v2sender@example.com",
                    "Destination": {
                        "ToAddresses": ["recipient@example.com"]
                    },
                    "Content": {
                        "Raw": {
                            "Data": "Subject: Raw V2\\r\\n\\r\\nRaw body"
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    @Order(10)
    void sendEmail_missingContent() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "v2sender@example.com",
                    "Destination": {
                        "ToAddresses": ["recipient@example.com"]
                    },
                    "Content": {}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(11)
    void getAccount() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(true))
            .body("ProductionAccessEnabled", equalTo(true))
            .body("SendQuota.Max24HourSend", notNullValue())
            .body("SendQuota.MaxSendRate", notNullValue())
            .body("SendQuota.SentLast24Hours", notNullValue());
    }

    @Test
    @Order(12)
    void deleteEmailIdentity() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("size()", equalTo(0)); // empty JSON object {}

        // Verify it's gone
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void sendEmail_template() {
        // Re-create an identity for template test
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "template-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "template-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["recipient@example.com"]
                    },
                    "Content": {
                        "Template": {
                            "TemplateName": "MyTemplate",
                            "TemplateData": "{\\"name\\": \\"World\\"}"
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    // ──────────────── DKIM Attributes ────────────────

    @Test
    @Order(20)
    void putDkimAttributes_enable() {
        // Create identity first
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "dkim-test@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        // Enable DKIM
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/dkim")
        .then()
            .statusCode(200);

        // Verify DKIM is enabled on the identity
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("DkimAttributes.SigningEnabled", equalTo(true))
            .body("DkimAttributes.Status", equalTo("Success"));
    }

    @Test
    @Order(21)
    void putDkimAttributes_disable() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/dkim")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("DkimAttributes.SigningEnabled", equalTo(false))
            .body("DkimAttributes.Status", equalTo("NotStarted"));
    }

    @Test
    @Order(22)
    void putDkimAttributes_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/nonexistent@example.com/dkim")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    // ──────────────── Feedback Attributes ────────────────

    @Test
    @Order(40)
    void putFeedbackAttributes_disable() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailForwardingEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/feedback")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("FeedbackForwardingStatus", equalTo(false));
    }

    @Test
    @Order(41)
    void putFeedbackAttributes_enable() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailForwardingEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/feedback")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("FeedbackForwardingStatus", equalTo(true));
    }

    @Test
    @Order(42)
    void putFeedbackAttributes_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailForwardingEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/nonexistent@example.com/feedback")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    // ──────────────── Account Sending ────────────────

    @Test
    @Order(50)
    void putAccountSendingAttributes_disable() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SendingEnabled": false}
                """)
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(false));
    }

    @Test
    @Order(51)
    void putAccountSendingAttributes_enable() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"SendingEnabled": true}
                """)
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(true));
    }

    @Test
    @Order(52)
    void putAccountSendingAttributes_missingField() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(57)
    void sendEmail_ccOnly_noToAddresses() {
        // Re-create identity
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "cc-only-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        // Send with only CcAddresses (no ToAddresses)
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "cc-only-sender@example.com",
                    "Destination": {
                        "CcAddresses": ["cc-recipient@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "CC Only Test"},
                            "Body": {"Text": {"Data": "Hello via CC"}}
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    // ──────────────── Validation edge cases ────────────────

    @Test
    @Order(53)
    void putDkimAttributes_missingField() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .put("/v2/email/identities/dkim-test@example.com/dkim")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(54)
    void putFeedbackAttributes_missingField() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .put("/v2/email/identities/dkim-test@example.com/feedback")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(55)
    void sendEmail_raw_missingData() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "FromEmailAddress": "v2sender@example.com",
                    "Destination": {"ToAddresses": ["r@example.com"]},
                    "Content": {"Raw": {}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(56)
    void sendEmail_raw_missingFrom() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "Destination": {"ToAddresses": ["r@example.com"]},
                    "Content": {"Raw": {"Data": "Subject: test"}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    // ──────────────── GetEmailIdentity full response ────────────────

    @Test
    @Order(60)
    void getEmailIdentity_fullResponse() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("EMAIL_ADDRESS"))
            .body("VerifiedForSendingStatus", equalTo(true))
            .body("VerificationStatus", equalTo("Success"))
            .body("FeedbackForwardingStatus", notNullValue())
            .body("DkimAttributes", notNullValue())
            .body("DkimAttributes.SigningEnabled", notNullValue())
            .body("DkimAttributes.Status", notNullValue())
            .body("MailFromAttributes", notNullValue())
            .body("MailFromAttributes.MailFromDomainStatus", equalTo("NOT_STARTED"))
            .body("MailFromAttributes.BehaviorOnMxFailure", equalTo("USE_DEFAULT_VALUE"))
            .body("Policies", notNullValue())
            .body("Tags", notNullValue());
    }
}
