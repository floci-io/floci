package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for the SES V1 Query-protocol {@code SendCustomVerificationEmail}: a successful
 * send returns a MessageId and registers the recipient as a pending-verification identity, and a
 * missing template fails with the v1-native {@code CustomVerificationEmailTemplateDoesNotExist} code
 * and the v1 send message ("Template <name> does not exist"), both verified against real AWS.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSendCustomVerificationEmailV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String FROM = "scve-v1-sender@floci.test";
    private static final String RECIPIENT = "scve-v1-recipient@floci.test";
    private static final String NAME = "scve-v1-template";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(0)
    void setup_verifyFromAndCreateTemplate() {
        query("VerifyEmailIdentity").formParam("EmailAddress", FROM).when().post("/").then().statusCode(200);
        query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", NAME)
                .formParam("FromEmailAddress", FROM)
                .formParam("TemplateSubject", "Verify your email")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(1)
    void send_returnsMessageId() {
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", RECIPIENT)
                .formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(200)
                .body(containsString("<SendCustomVerificationEmailResponse"))
                .body(containsString("<MessageId>"));
    }

    @Test
    @Order(2)
    void send_registersRecipientAsPendingIdentity() {
        // AWS registers the recipient as a pending-verification identity as part of the send.
        query("GetIdentityVerificationAttributes")
                .formParam("Identities.member.1", RECIPIENT)
        .when().post("/").then().statusCode(200)
                .body(containsString("<VerificationStatus>Pending</VerificationStatus>"));
    }

    @Test
    @Order(3)
    void send_missingTemplate_returnsDoesNotExistWithV1Message() {
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", RECIPIENT)
                .formParam("TemplateName", "scve-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"))
                .body(containsString("Template &lt;scve-v1-ghost&gt; does not exist"));
    }

    @Test
    @Order(4)
    void send_templateFromNoLongerVerified_returnsFromEmailAddressNotVerified() {
        // The template's From was verified at create time; once that identity is deleted, sending
        // through the template fails with the v1-native FromEmailAddressNotVerified / 400.
        String from2 = "scve-v1-stale-from@floci.test";
        String template2 = "scve-v1-stale-template";
        query("VerifyEmailIdentity").formParam("EmailAddress", from2).when().post("/").then().statusCode(200);
        query("CreateCustomVerificationEmailTemplate")
                .formParam("TemplateName", template2)
                .formParam("FromEmailAddress", from2)
                .formParam("TemplateSubject", "Verify your email")
                .formParam("TemplateContent", "<html><body>verify</body></html>")
                .formParam("SuccessRedirectionURL", "https://example.com/ok")
                .formParam("FailureRedirectionURL", "https://example.com/fail")
        .when().post("/").then().statusCode(200);
        query("DeleteIdentity").formParam("Identity", from2).when().post("/").then().statusCode(200);

        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", RECIPIENT)
                .formParam("TemplateName", template2)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>FromEmailAddressNotVerified</Code>"));
    }

    @Test
    @Order(5)
    void send_accountSendingDisabled_returnsAccountSendingPaused() {
        // Like every other v1 send action, the send is refused while account sending is paused.
        query("UpdateAccountSendingEnabled").formParam("Enabled", "false")
        .when().post("/").then().statusCode(200);
        try {
            query("SendCustomVerificationEmail")
                    .formParam("EmailAddress", RECIPIENT)
                    .formParam("TemplateName", NAME)
            .when().post("/").then().statusCode(400)
                    .body(containsString("<Code>AccountSendingPausedException</Code>"));
        } finally {
            query("UpdateAccountSendingEnabled").formParam("Enabled", "true")
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(6)
    void send_malformedEmailAddress_returnsInvalidParameterValue() {
        // AWS validates the recipient address before the template check: a value without a single
        // '@' is InvalidParameterValue / 400 "Invalid email address<addr>." (verified against AWS).
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", "not-an-email")
                .formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Invalid email address&lt;not-an-email&gt;."));
    }

    @Test
    @Order(7)
    void send_blankEmailAddress_returnsNotSpecified() {
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", "")
                .formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Email address not specified."));
    }

    @Test
    @Order(8)
    void send_multipleUnquotedAt_returnsInvalidParameterValue() {
        // AWS rejects an address with more than one unquoted '@' (verified).
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", "a@@b.com")
                .formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Invalid email address&lt;a@@b.com&gt;."));
    }

    @Test
    @Order(9)
    void send_quotedLocalPartWithAt_passesAddressValidation() {
        // AWS accepts an RFC-5321 quoted local part that contains '@' (verified), so it must not be
        // rejected as invalid. A non-existent template proves the address passed validation and the
        // call reached the template-existence check.
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", "\"a@b\"@example.com")
                .formParam("TemplateName", "scve-v1-ghost2")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CustomVerificationEmailTemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(10)
    void send_quoteClosesBeforeMultipleAt_isRejected() {
        // The quoted local part closes before the two '@', so both separators are unquoted — invalid.
        // Guards against a naive "starts with a quote" exemption that would let this through.
        query("SendCustomVerificationEmail")
                .formParam("EmailAddress", "\"a\"@@example.com")
                .formParam("TemplateName", NAME)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(11)
    void send_emptyLocalOrDomain_returnsInvalidParameterValue() {
        // AWS rejects a boundary '@' — empty local part (@example.com) or empty domain (local@).
        for (String bad : new String[] {"@example.com", "local@"}) {
            query("SendCustomVerificationEmail")
                    .formParam("EmailAddress", bad)
                    .formParam("TemplateName", NAME)
            .when().post("/").then().statusCode(400)
                    .body(containsString("<Code>InvalidParameterValue</Code>"))
                    .body(containsString("Invalid email address&lt;" + bad + "&gt;."));
        }
    }
}
