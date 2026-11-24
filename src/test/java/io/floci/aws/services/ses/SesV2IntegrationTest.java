package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.floci.aws.services.ses.SesV2Requests.AUTH;
import static io.floci.aws.services.ses.SesV2Requests.v2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SES V2 via the REST JSON protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesV2IntegrationTest {

    @Test
    @Order(1)
    void createEmailIdentity_email() {
        v2()
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
        v2()
            .body("""
                {"EmailIdentity": "v2example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("DOMAIN"))
            .body("VerifiedForSendingStatus", equalTo(false))
            .body("DkimAttributes.SigningEnabled", equalTo(true))
            // CreateEmailIdentity reports NOT_STARTED (SES hasn't begun tracking the CNAMEs yet).
            .body("DkimAttributes.Status", equalTo("NOT_STARTED"))
            .body("DkimAttributes.Tokens", not(empty()));

        // A subsequent GetEmailIdentity transitions the DKIM status to PENDING, matching AWS.
        v2()
        .when()
            .get("/v2/email/identities/v2example.com")
        .then()
            .statusCode(200)
            .body("DkimAttributes.Status", equalTo("PENDING"));
    }

    @Test
    @Order(3)
    void createEmailIdentity_missingField() {
        v2()
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
        // Other classes share this region, so ask for a page large enough to hold every identity.
        v2()
            .queryParam("PageSize", 1000)
        .when()
            .get("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("EmailIdentities", notNullValue())
            .body("EmailIdentities.size()", greaterThanOrEqualTo(2))
            .body("EmailIdentities.IdentityName", hasItem("v2sender@example.com"))
            .body("EmailIdentities.IdentityName", hasItem("v2example.com"))
            // VerificationStatus must be populated per identity (never null), matching AWS.
            .body("EmailIdentities.VerificationStatus", everyItem(notNullValue()))
            .body("EmailIdentities.find { it.IdentityName == 'v2sender@example.com' }.VerificationStatus",
                    equalTo("SUCCESS"))
            .body("EmailIdentities.find { it.IdentityName == 'v2example.com' }.VerificationStatus",
                    equalTo("PENDING"))
            // SendingEnabled tracks verification: SUCCESS -> true, PENDING -> false (matches AWS).
            .body("EmailIdentities.find { it.IdentityName == 'v2sender@example.com' }.SendingEnabled",
                    equalTo(true))
            .body("EmailIdentities.find { it.IdentityName == 'v2example.com' }.SendingEnabled",
                    equalTo(false));
    }

    @Test
    @Order(5)
    void getEmailIdentity() {
        v2()
        .when()
            .get("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("EMAIL_ADDRESS"))
            .body("VerifiedForSendingStatus", equalTo(true))
            .body("DkimAttributes", notNullValue());
    }

    @Test
    @Order(61)
    void getEmailIdentity_domainTransitionsToSuccessWhenDkimRecordsExist() {
        List<String> tokens = v2()
            .body("""
                {"EmailIdentity": "v2dkim-success.floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .body("VerifiedForSendingStatus", equalTo(false))
            .extract()
            .jsonPath()
            .getList("DkimAttributes.Tokens", String.class);

        SesDomainIdentityTestHelper.publishDkimRecords("v2dkim-success.floci.test", tokens);

        v2()
        .when()
            .get("/v2/email/identities/v2dkim-success.floci.test")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("DOMAIN"))
            .body("VerifiedForSendingStatus", equalTo(true))
            .body("VerificationStatus", equalTo("SUCCESS"))
            .body("DkimAttributes.SigningEnabled", equalTo(true))
            .body("DkimAttributes.Status", equalTo("SUCCESS"))
            .body("DkimAttributes.Tokens", not(empty()));

        // DKIM verification status tracks DNS detection independently of the signing flag: disabling
        // DKIM keeps Status=SUCCESS while the CNAMEs remain published.
        v2()
            .body("""
                {"SigningEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/v2dkim-success.floci.test/dkim")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/identities/v2dkim-success.floci.test")
        .then()
            .statusCode(200)
            .body("DkimAttributes.SigningEnabled", equalTo(false))
            .body("DkimAttributes.Status", equalTo("SUCCESS"));

        // Re-enable so the key-rotation assertion below starts from the signing-enabled state.
        v2()
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/v2dkim-success.floci.test/dkim")
        .then()
            .statusCode(200);

        // Rotating the DKIM key must not revoke the identity's sending verification (matching AWS):
        // VerificationStatus stays SUCCESS, but DKIM re-pends until the new tokens' records are detected.
        v2()
            .body("""
                {"SigningAttributesOrigin":"AWS_SES","SigningAttributes":{"NextSigningKeyLength":"RSA_1024_BIT"}}
                """)
        .when()
            .put("/v2/email/identities/v2dkim-success.floci.test/dkim/signing")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/identities/v2dkim-success.floci.test")
        .then()
            .statusCode(200)
            .body("VerificationStatus", equalTo("SUCCESS"))
            .body("DkimAttributes.Status", equalTo("PENDING"));
    }

    @Test
    @Order(6)
    void getEmailIdentity_notFound() {
        v2()
        .when()
            .get("/v2/email/identities/nonexistent@example.com")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void sendEmail_simple() {
        v2()
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
        v2()
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
        v2()
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
        v2()
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
        // SuppressionAttributes defaults to BOUNCE + COMPLAINT on a fresh region,
        // matching real SES account behaviour. Later @Order(58+) tests overwrite
        // the stored value, so this assertion only holds before those run.
        v2()
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(true))
            .body("ProductionAccessEnabled", equalTo(true))
            .body("SendQuota.Max24HourSend", notNullValue())
            .body("SendQuota.MaxSendRate", notNullValue())
            .body("SendQuota.SentLast24Hours", notNullValue())
            .body("SuppressionAttributes.SuppressedReasons",
                  containsInAnyOrder("BOUNCE", "COMPLAINT"));
    }

    @Test
    @Order(12)
    void deleteEmailIdentity() {
        v2()
        .when()
            .delete("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("size()", equalTo(0)); // empty JSON object {}

        // Verify it's gone
        v2()
        .when()
            .get("/v2/email/identities/v2sender@example.com")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void sendEmail_template() {
        v2()
            .body("""
                {"EmailIdentity": "template-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        v2()
            .body("""
                {
                    "TemplateName": "MyTemplate",
                    "TemplateContent": {
                        "Subject": "Hello {{name}}",
                        "Text": "Hi {{name}}!"
                    }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        v2()
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

    @Test
    @Order(14)
    void createEmailIdentity_rejectsLeadingTrailingWhitespace() {
        v2()
            .body("""
                {"EmailIdentity": " padded@example.com "}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", containsString("leading or trailing whitespace"));
    }

    @Test
    @Order(15)
    void createEmailIdentity_withTags_visibleViaGetAndListTags() {
        // Tags supplied to CreateEmailIdentity must round-trip through GetEmailIdentity
        // and ListTagsForResource (template/configuration-set behaviour parallel).
        v2()
            .body("""
                {
                    "EmailIdentity": "v2-tag-roundtrip.floci.test",
                    "Tags": [
                        {"Key": "team", "Value": "platform"},
                        {"Key": "env", "Value": "stg"}
                    ]
                }
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/identities/v2-tag-roundtrip.floci.test")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("stg"));

        String arn = "arn:aws:ses:us-east-1:000000000000:identity/v2-tag-roundtrip.floci.test";
        given()
            .header("Authorization", AUTH)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));
    }

    // ──────────────── DKIM Attributes ────────────────

    @Test
    @Order(20)
    void putDkimAttributes_enable() {
        // Create identity first
        v2()
            .body("""
                {"EmailIdentity": "dkim-test@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        // Enable DKIM
        v2()
            .body("""
                {"SigningEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/dkim")
        .then()
            .statusCode(200);

        // Verify DKIM is enabled on the identity
        v2()
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("DkimAttributes.SigningEnabled", equalTo(true))
            // example.com is not a DKIM-verified domain identity, so the enabled flag is set but the
            // DKIM verification status stays NOT_STARTED (status tracks DNS detection, not the flag).
            .body("DkimAttributes.Status", equalTo("NOT_STARTED"));
    }

    @Test
    @Order(21)
    void putDkimAttributes_disable() {
        v2()
            .body("""
                {"SigningEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/dkim")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("DkimAttributes.SigningEnabled", equalTo(false))
            .body("DkimAttributes.Status", equalTo("NOT_STARTED"));
    }

    // ──────────────── Feedback Attributes ────────────────

    @Test
    @Order(40)
    void putFeedbackAttributes_disable() {
        v2()
            .body("""
                {"EmailForwardingEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/feedback")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("FeedbackForwardingStatus", equalTo(false));
    }

    @Test
    @Order(41)
    void putFeedbackAttributes_enable() {
        v2()
            .body("""
                {"EmailForwardingEnabled": true}
                """)
        .when()
            .put("/v2/email/identities/dkim-test@example.com/feedback")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("FeedbackForwardingStatus", equalTo(true));
    }

    @Test
    @Order(42)
    void putFeedbackAttributes_notFound() {
        // Real SES v2 returns BadRequestException (HTTP 400) for an unknown
        // identity on this endpoint, with the "Identity X is invalid..."
        // message inherited from the v1 SetIdentityFeedbackForwardingEnabled
        // wire shape via remapV1Exception.
        v2()
            .body("""
                {"EmailForwardingEnabled": false}
                """)
        .when()
            .put("/v2/email/identities/nonexistent@example.com/feedback")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    // ──────────────── Account Sending ────────────────

    @Test
    @Order(50)
    void putAccountSendingAttributes_disable() {
        v2()
            .body("""
                {"SendingEnabled": false}
                """)
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(false));
    }

    @Test
    @Order(51)
    void putAccountSendingAttributes_enable() {
        v2()
            .body("""
                {"SendingEnabled": true}
                """)
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);

        v2()
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SendingEnabled", equalTo(true));
    }

    @Test
    @Order(58)
    void putAccountSuppressionAttributes_storesReasons_visibleViaGetAccount() {
        v2()
            .body("""
                {"SuppressedReasons": ["BOUNCE", "COMPLAINT"]}
                """)
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SuppressionAttributes.SuppressedReasons", containsInAnyOrder("BOUNCE", "COMPLAINT"));
    }

    @Test
    @Order(59)
    void putAccountSuppressionAttributes_emptyArray_clearsReasons() {
        v2()
            .body("""
                {"SuppressedReasons": []}
                """)
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SuppressionAttributes.SuppressedReasons", hasSize(0));
    }

    @Test
    @Order(61)
    void putAccountSuppressionAttributes_missingField_succeedsWithEmptyList() {
        v2()
            .body("{}")
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SuppressionAttributes.SuppressedReasons", hasSize(0));
    }

    @Test
    @Order(62)
    void putAccountSuppressionAttributes_invalidReason_returns400() {
        v2()
            .body("""
                {"SuppressedReasons": ["BOUNCE", "OTHER"]}
                """)
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", containsString(
                    "1 validation error detected: Value at 'suppressedReasons' failed to satisfy constraint: "
                            + "Member must satisfy constraint: [Member must satisfy enum value set: [BOUNCE, COMPLAINT]]"));
    }

    @Test
    @Order(64)
    void putAccountSuppressionAttributes_nullMember_returns400() {
        v2()
            .body("""
                {"SuppressedReasons": [null]}
                """)
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(66)
    void putAccountSuppressionAttributes_acceptsValidationAttributes() {
        // The optional ValidationAttributes object is accepted but not parsed —
        // verifying the request shape does not regress to a 400.
        v2()
            .body("""
                {
                    "SuppressedReasons": ["BOUNCE"],
                    "ValidationAttributes": {
                        "ConditionThreshold": {
                            "ConditionThresholdEnabled": "ENABLED",
                            "OverallConfidenceThreshold": {
                                "ConfidenceVerdictThreshold": "HIGH"
                            }
                        }
                    }
                }
                """)
        .when()
            .put("/v2/email/account/suppression")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/account")
        .then()
            .statusCode(200)
            .body("SuppressionAttributes.SuppressedReasons", contains("BOUNCE"));
    }

    @Test
    @Order(57)
    void sendEmail_ccOnly_noToAddresses() {
        // Re-create identity
        v2()
            .body("""
                {"EmailIdentity": "cc-only-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        // Send with only CcAddresses (no ToAddresses)
        v2()
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
    @Order(55)
    void sendEmail_raw_missingData() {
        v2()
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
        v2()
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
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Missing required header 'From'."));
    }

    // FromEmailAddress is optional in the AWS v2 contract; the per-content-type behavior
    // below is verified against real AWS (Raw takes its From from the MIME message; Simple
    // returns a null message; Templated returns "Source cannot be empty").

    @Test
    @Order(67)
    void sendEmail_raw_noFromAddress_usesMimeFrom() {
        v2()
            .body("""
                {"EmailIdentity": "raw-from@floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        v2()
            .body("""
                {
                    "Destination": {"ToAddresses": ["r@example.com"]},
                    "Content": {"Raw": {"Data": "From: raw-from@floci.test\\r\\nTo: r@example.com\\r\\nSubject: s\\r\\n\\r\\nbody"}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());
    }

    @Test
    @Order(68)
    void sendEmail_simple_missingFrom() {
        v2()
            .body("""
                {
                    "Destination": {"ToAddresses": ["r@example.com"]},
                    "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "hi"}}}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            // AWS returns the message key present with a JSON null value.
            .body("$", hasKey("message"))
            .body("message", nullValue());
    }

    @Test
    @Order(69)
    void sendEmail_template_missingFrom() {
        v2()
            .body("""
                {
                    "Destination": {"ToAddresses": ["r@example.com"]},
                    "Content": {"Template": {"TemplateName": "any-tpl", "TemplateData": "{}"}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Source cannot be empty"));
    }

    // ──────────────── Inspection endpoint (/_aws/ses) ────────────────

    @Test
    @Order(70)
    void inspectionEndpoint_textAndHtmlAreStoredSeparately() {
        // Create identity
        v2()
            .body("""
                {"EmailIdentity": "inspect-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        // Clear any previous messages
        given().delete("/_aws/ses").then().statusCode(200);

        // Send with distinct Text and Html bodies
        String messageId = v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "Inspect Test"},
                            "Body": {
                                "Text": {"Data": "plain text body"},
                                "Html": {"Data": "<p>html body</p>"}
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
        .extract()
            .path("MessageId");

        // Verify via inspection endpoint
        given()
        .when()
            .get("/_aws/ses?id=" + messageId)
        .then()
            .statusCode(200)
            .body("messages[0].Body.text_part", equalTo("plain text body"))
            .body("messages[0].Body.html_part", equalTo("<p>html body</p>"));
    }

    @Test
    @Order(71)
    void inspectionEndpoint_textOnlyEmail() {
        given().delete("/_aws/ses").then().statusCode(200);

        v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "Text Only"},
                            "Body": {
                                "Text": {"Data": "only text"}
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Body.text_part", equalTo("only text"))
            .body("messages[0].Body.html_part", nullValue());
    }

    @Test
    @Order(72)
    void inspectionEndpoint_htmlOnlyEmail() {
        given().delete("/_aws/ses").then().statusCode(200);

        v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "Html Only"},
                            "Body": {
                                "Html": {"Data": "<b>only html</b>"}
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Body.text_part", nullValue())
            .body("messages[0].Body.html_part", equalTo("<b>only html</b>"));
    }

    @Test
    @Order(73)
    void inspectionEndpoint_replyToAddressesAreStored() {
        given().delete("/_aws/ses").then().statusCode(200);

        v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "ReplyToAddresses": ["reply1@example.com", "reply2@example.com"],
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "ReplyTo Test"},
                            "Body": {"Text": {"Data": "hello"}}
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].ReplyToAddresses", hasItems("reply1@example.com", "reply2@example.com"));
    }

    @Test
    @Order(74)
    void inspectionEndpoint_noReplyToOmitsField() {
        given().delete("/_aws/ses").then().statusCode(200);

        v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "No ReplyTo"},
                            "Body": {"Text": {"Data": "hello"}}
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0]", not(hasKey("ReplyToAddresses")));
    }

    @Test
    @Order(75)
    void inspectionEndpoint_rawEmailReturnsRawData() {
        given().delete("/_aws/ses").then().statusCode(200);

        v2()
            .body("""
                {
                    "FromEmailAddress": "inspect-sender@example.com",
                    "Destination": {
                        "ToAddresses": ["inspect-to@example.com"]
                    },
                    "Content": {
                        "Raw": {
                            "Data": "Subject: Raw\\r\\n\\r\\nRaw body"
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].RawData", notNullValue())
            .body("messages[0].RawData", containsString("Raw body"))
            .body("messages[0]", not(hasKey("Destination")))
            .body("messages[0]", not(hasKey("Subject")))
            .body("messages[0]", not(hasKey("Body")));
    }

    @Test
    @Order(80)
    void inspectionEndpoint_returnsEmailsFromAllRegions() {
        given().delete("/_aws/ses").then().statusCode(200);

        // Identities are regional, so the sender is verified in each region it sends from.
        for (String region : List.of("us-east-1", "ap-northeast-1")) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SesV1Requests.authFor(region))
                .formParam("Action", "VerifyEmailIdentity")
                .formParam("EmailAddress", "multi@example.com")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        // Send from us-east-1
        v2()
            .body("""
                {
                    "FromEmailAddress": "multi@example.com",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "US East"},
                            "Body": {"Text": {"Data": "from us-east-1"}}
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        // Send from ap-northeast-1
        v2(SesV2Requests.authFor("ap-northeast-1"))
            .body("""
                {
                    "FromEmailAddress": "multi@example.com",
                    "Destination": {"ToAddresses": ["to@example.com"]},
                    "Content": {
                        "Simple": {
                            "Subject": {"Data": "AP NE"},
                            "Body": {"Text": {"Data": "from ap-northeast-1"}}
                        }
                    }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200);

        // Inspection returns both, each with correct Region
        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.size()", equalTo(2))
            .body("messages.find { it.Subject == 'US East' }.Region", equalTo("us-east-1"))
            .body("messages.find { it.Subject == 'AP NE' }.Region", equalTo("ap-northeast-1"));
    }

    // ──────────────── GetEmailIdentity full response ────────────────

    @Test
    @Order(60)
    void getEmailIdentity_fullResponse() {
        v2()
        .when()
            .get("/v2/email/identities/dkim-test@example.com")
        .then()
            .statusCode(200)
            .body("IdentityType", equalTo("EMAIL_ADDRESS"))
            .body("VerifiedForSendingStatus", equalTo(true))
            .body("VerificationStatus", equalTo("SUCCESS"))
            .body("FeedbackForwardingStatus", notNullValue())
            .body("DkimAttributes", notNullValue())
            .body("DkimAttributes.SigningEnabled", notNullValue())
            .body("DkimAttributes.Status", notNullValue())
            .body("MailFromAttributes.BehaviorOnMxFailure", equalTo("USE_DEFAULT_VALUE"))
            .body("MailFromAttributes.MailFromDomain", nullValue())
            .body("MailFromAttributes.MailFromDomainStatus", nullValue())
            .body("Policies", notNullValue())
            .body("Tags", notNullValue());
    }
}
