package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the SES V2 REST JSON {@code SendCustomVerificationEmail}
 * ({@code POST /v2/email/outbound-custom-verification-emails}): a successful send returns a
 * MessageId, stores the rendered email in the inspection mailbox, and registers the recipient as a
 * pending identity; a missing template is a {@code NotFoundException}/404 and an unknown
 * configuration set is likewise remapped to 404, both verified against real AWS.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSendCustomVerificationEmailV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String FROM = "scve-v2-sender@floci.test";
    private static final String RECIPIENT = "scve-v2-recipient@floci.test";
    private static final String NAME = "scve-v2-template";
    private static final String SUBJECT = "Verify your email V2";
    private static final String SEND = "/v2/email/outbound-custom-verification-emails";

    @Test
    @Order(0)
    void setup_verifyFromAndCreateTemplate() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + FROM + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "TemplateName": "%s",
                      "FromEmailAddress": "%s",
                      "TemplateSubject": "%s",
                      "TemplateContent": "<html><body>verify</body></html>",
                      "SuccessRedirectionURL": "https://example.com/ok",
                      "FailureRedirectionURL": "https://example.com/fail"
                    }
                    """.formatted(NAME, FROM, SUBJECT))
        .when().post("/v2/email/custom-verification-email-templates").then().statusCode(200);
    }

    @Test
    @Order(1)
    void send_returnsMessageId() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + RECIPIENT + "\",\"TemplateName\":\"" + NAME + "\"}")
        .when().post(SEND).then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(2)
    void send_storesRenderedEmailInInspectionMailbox() {
        given().header("Authorization", AUTH).queryParam("email", RECIPIENT)
        .when().get("/_aws/ses").then().statusCode(200)
                .body(containsString(FROM))
                .body(containsString(SUBJECT))
                // The template body is passed through verbatim (no placeholder substitution)...
                .body(containsString("<html><body>verify</body></html>"))
                // ...followed by the fixed, non-removable disclaimer AWS always appends.
                .body(containsString(
                        "If you did not request to verify this email address, please disregard this message."));
    }

    @Test
    @Order(3)
    void send_registersRecipientAsIdentity() {
        // The recipient is registered (pending) as part of the send, so GetEmailIdentity finds it.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/identities/" + RECIPIENT).then().statusCode(200);
    }

    @Test
    @Order(4)
    void send_missingTemplate_returnsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + RECIPIENT + "\",\"TemplateName\":\"scve-v2-ghost\"}")
        .when().post(SEND).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                // v2 uses a longer not-found message than the v1 send ("Template <name>..."), verified.
                .body("message", equalTo(
                        "Custom verification email template <scve-v2-ghost> does not exist"));
    }

    @Test
    @Order(5)
    void send_unknownConfigurationSet_returnsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + RECIPIENT + "\",\"TemplateName\":\"" + NAME
                        + "\",\"ConfigurationSetName\":\"scve-v2-ghost-cs\"}")
        .when().post(SEND).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(6)
    void send_missingEmailAddress_returnsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TemplateName\":\"" + NAME + "\"}")
        .when().post(SEND).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void send_templateFromNoLongerVerified_remapsToNotFound() {
        // The template's From was verified at create time; once that identity is deleted, the send
        // fails FromEmailAddressNotVerified, which the v2 boundary remaps to NotFoundException / 404
        // (consistent with the CreateCustomVerificationEmailTemplate unverified-From mapping).
        String from2 = "scve-v2-stale-from@floci.test";
        String template2 = "scve-v2-stale-template";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + from2 + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "TemplateName": "%s",
                      "FromEmailAddress": "%s",
                      "TemplateSubject": "%s",
                      "TemplateContent": "<html><body>verify</body></html>",
                      "SuccessRedirectionURL": "https://example.com/ok",
                      "FailureRedirectionURL": "https://example.com/fail"
                    }
                    """.formatted(template2, from2, SUBJECT))
        .when().post("/v2/email/custom-verification-email-templates").then().statusCode(200);
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/identities/" + from2).then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + RECIPIENT + "\",\"TemplateName\":\"" + template2 + "\"}")
        .when().post(SEND).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(8)
    void send_accountSendingDisabled_returnsSendingPaused() {
        // Like the v2 SendEmail endpoint, the send is refused while account sending is paused.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"SendingEnabled\":false}")
        .when().put("/v2/email/account/sending").then().statusCode(200);
        try {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"EmailAddress\":\"" + RECIPIENT + "\",\"TemplateName\":\"" + NAME + "\"}")
            .when().post(SEND).then().statusCode(400)
                    .body("__type", equalTo("SendingPausedException"));
        } finally {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"SendingEnabled\":true}")
            .when().put("/v2/email/account/sending").then().statusCode(200);
        }
    }

    @Test
    @Order(9)
    void send_malformedEmailAddress_returnsBadRequestWithAwsMessage() {
        // AWS returns the same "Invalid email address<addr>." message on v2 as on v1 (verified);
        // Floci throws the v1-native InvalidParameterValue which remaps to BadRequestException / 400.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"not-an-email\",\"TemplateName\":\"" + NAME + "\"}")
        .when().post(SEND).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid email address<not-an-email>."));
    }

    @Test
    @Order(10)
    void send_multipleUnquotedAt_returnsBadRequest() {
        // AWS rejects more than one unquoted '@'; a quoted local part that contains '@' is accepted
        // (see the v1 test) — the check must only reject the unquoted case.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"a@@b.com\",\"TemplateName\":\"" + NAME + "\"}")
        .when().post(SEND).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid email address<a@@b.com>."));
    }

    @Test
    @Order(11)
    void send_emptyDomain_returnsBadRequest() {
        // A trailing '@' is an empty domain, which AWS rejects (verified).
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"local@\",\"TemplateName\":\"" + NAME + "\"}")
        .when().post(SEND).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid email address<local@>."));
    }
}
