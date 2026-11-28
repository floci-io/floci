package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.floci.aws.services.ses.SesV2Requests.post;
import static io.floci.aws.services.ses.SesV2Requests.v2;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Where a paused account is refused among the v2 send checks (probe-confirmed): SendEmail with Simple
 * or Template content refuses it first, SendEmail with Raw content and SendBulkEmail only after the
 * sender check.
 */
@QuarkusTest
class SesAccountSendingPausedV2IntegrationTest {

    private static final String VERIFIED = "paused-v2-verified@floci.test";
    private static final String UNVERIFIED = "paused-v2-unverified@floci.test";
    private static final String PAUSED = "Sending is paused for this account.";
    private static final String NOT_VERIFIED = "Email address is not verified.";

    @BeforeEach
    void pauseSending() {
        post("/v2/email/identities", "{\"EmailIdentity\":\"" + VERIFIED + "\"}");
        setSending(false);
    }

    @AfterEach
    void resumeSending() {
        setSending(true);
    }

    @Test
    void sendEmailSimple_unverifiedSender_isRefusedByThePause() {
        post("/v2/email/outbound-emails", """
                {"FromEmailAddress": "%s", "Destination": {"ToAddresses": ["to@example.com"]},
                 "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}}
                """.formatted(UNVERIFIED))
            .statusCode(400)
            .body("__type", equalTo("SendingPausedException"))
            .body("message", equalTo(PAUSED));
    }

    @Test
    void sendEmailTemplate_unverifiedSenderAndMissingTemplate_isRefusedByThePause() {
        post("/v2/email/outbound-emails", """
                {"FromEmailAddress": "%s", "Destination": {"ToAddresses": ["to@example.com"]},
                 "Content": {"Template": {"TemplateName": "paused-v2-missing", "TemplateData": "{}"}}}
                """.formatted(UNVERIFIED))
            .statusCode(400)
            .body("__type", equalTo("SendingPausedException"))
            .body("message", equalTo(PAUSED));
    }

    @Test
    void sendEmail_malformedBody_isRefusedByThePause() {
        post("/v2/email/outbound-emails", "{not json")
            .statusCode(400)
            .body("__type", equalTo("SendingPausedException"));
    }

    @Test
    void sendEmailRaw_unverifiedSender_isRejectedBeforeThePause() {
        post("/v2/email/outbound-emails", rawBody(UNVERIFIED))
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", startsWith(NOT_VERIFIED));
    }

    @Test
    void sendEmailRaw_verifiedSender_isRefusedByThePause() {
        post("/v2/email/outbound-emails", rawBody(VERIFIED))
            .statusCode(400)
            .body("__type", equalTo("SendingPausedException"))
            .body("message", equalTo(PAUSED));
    }

    @Test
    void sendBulkEmail_unverifiedSender_isRejectedBeforeThePause() {
        post("/v2/email/outbound-bulk-emails", bulkBody(UNVERIFIED))
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"))
            .body("message", startsWith(NOT_VERIFIED));
    }

    @Test
    void sendBulkEmail_verifiedSender_isRefusedByThePause() {
        post("/v2/email/outbound-bulk-emails", bulkBody(VERIFIED))
            .statusCode(400)
            .body("__type", equalTo("SendingPausedException"))
            .body("message", equalTo(PAUSED));
    }

    private static String rawBody(String sender) {
        String mime = "From: " + sender + "\r\nTo: to@example.com\r\nSubject: s\r\n\r\nbody";
        String data = Base64.getEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        return "{\"Content\": {\"Raw\": {\"Data\": \"" + data + "\"}}}";
    }

    private static String bulkBody(String sender) {
        return """
                {"FromEmailAddress": "%s",
                 "DefaultContent": {"Template": {"TemplateContent": {"Subject": "s", "Text": "t"}}},
                 "BulkEmailEntries": [{"Destination": {"ToAddresses": ["to@example.com"]}}]}
                """.formatted(sender);
    }

    private static void setSending(boolean enabled) {
        v2()
            .body("{\"SendingEnabled\":" + enabled + "}")
        .when()
            .put("/v2/email/account/sending")
        .then()
            .statusCode(200);
    }
}
