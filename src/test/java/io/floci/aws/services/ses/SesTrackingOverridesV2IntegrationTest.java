package io.floci.aws.services.ses;

import io.floci.aws.testutil.SesMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ConfigurationOverrides.Tracking} on SendEmail and SendBulkEmail. Floci has no open or click
 * tracking, so a valid override is accepted and has no effect; an invalid one is refused the way
 * SES v2 refuses it (probed in us-west-2 on 2026-10-06).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SesTrackingOverridesV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-southeast-4/ses/aws4_request";
    // A sender of its own, verified once below: the identity outlives this class, and other SES
    // classes that share the application create their own senders.
    private static final String SENDER = "tracking-overrides-sender@floci.test";
    private static final String SEND = "/v2/email/outbound-emails";
    private static final String BULK = "/v2/email/outbound-bulk-emails";
    private static final String SIMPLE = "\"Content\": {\"Simple\": {\"Subject\": {\"Data\": \"s\"}, "
            + "\"Body\": {\"Text\": {\"Data\": \"b\"}}}}";
    private static final String OPEN_ENUM = "Value at 'configurationOverrides.tracking.openTrackingEnabled' "
            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]";
    private static final String CLICK_ENUM = "Value at 'configurationOverrides.tracking.clickTrackingEnabled' "
            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]";

    private boolean senderVerified;

    // Not @BeforeAll: the Quarkus test port is only bound once the first test starts.
    @BeforeEach
    void verifySender() {
        if (senderVerified) {
            return;
        }
        send("/v2/email/identities", "{\"EmailIdentity\": \"" + SENDER + "\"}").statusCode(200);
        senderVerified = true;
    }

    @Test
    void sendEmail_acceptsAValidOverride_andOneOfNothing() {
        for (String overrides : new String[] {
                "{\"Tracking\": {\"OpenTrackingEnabled\": \"DISABLED\", \"ClickTrackingEnabled\": \"ENABLED\"}}",
                "{\"Tracking\": {\"ClickTrackingEnabled\": \"DISABLED\", \"Foo\": \"x\"}, \"Bar\": {}}",
                "{}", "{\"Tracking\": {}}", "{\"Tracking\": null}", "null"}) {
            sendWith(overrides).statusCode(200).body("MessageId", notNullValue());
        }
    }

    @Test
    void sendEmail_acceptsAnOverrideOnRawContent() {
        String mime = "From: " + SENDER + "\r\nTo: alice@example.com\r\nSubject: raw\r\n\r\nbody\r\n";
        String data = Base64.getEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        send(SEND, "{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": "
                + "{\"ToAddresses\": [\"alice@example.com\"]}, \"Content\": {\"Raw\": {\"Data\": \"" + data + "\"}}, "
                + "\"ConfigurationOverrides\": {\"Tracking\": {\"OpenTrackingEnabled\": \"ENABLED\"}}}")
                .statusCode(200);
    }

    @Test
    void sendEmail_refusesAValueOutsideTheEnum_reportingBothClickFirst() {
        String recipient = "refused-send@tracking.test";
        sendWith("{\"Tracking\": {\"OpenTrackingEnabled\": \"disabled\"}}", recipient).statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: " + OPEN_ENUM));
        sendWith("{\"Tracking\": {\"OpenTrackingEnabled\": \"\", \"ClickTrackingEnabled\": \"yes\"}}", recipient)
                .statusCode(400)
                .body("message", equalTo("2 validation errors detected: " + CLICK_ENUM + "; " + OPEN_ENUM));
        assertNothingSentTo(recipient);
    }

    @Test
    void sendEmail_wrongJsonTypes_areSerializationExceptions() {
        sendWith("{\"Tracking\": {\"OpenTrackingEnabled\": 1}}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a String"));
        sendWith("{\"Tracking\": \"x\"}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Expected null"));
        sendWith("[]").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Start of list found where not expected"));
    }

    @Test
    void sendEmail_theEnumIsCheckedBeforeTheTagsAndTheConfigurationSet() {
        String request = "{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": {\"ToAddresses\": "
                + "[\"alice@example.com\"]}, " + SIMPLE + ", \"EmailTags\": [{\"Name\": \"a b\", \"Value\": \"v\"}], "
                + "\"ConfigurationSetName\": \"tracking-missing\", "
                + "\"ConfigurationOverrides\": {\"Tracking\": {\"OpenTrackingEnabled\": \"%s\"}}}";
        send(SEND, request.formatted("yes")).statusCode(400)
                .body("message", equalTo("1 validation error detected: " + OPEN_ENUM));
        // With a valid value the same request fails on the tags or the set, so the order above is real.
        send(SEND, request.formatted("ENABLED")).statusCode(not(200))
                .body("message", not(containsString("configurationOverrides")));
    }

    @Test
    void sendBulkEmail_acceptsAValidOverride_andRefusesAnInvalidOne() {
        send(BULK, bulkBody("{\"Tracking\": {\"OpenTrackingEnabled\": \"DISABLED\"}}")).statusCode(200)
                .body("BulkEmailEntryResults", hasSize(2))
                .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"));
        String recipient = "refused-bulk@tracking.test";
        send(BULK, bulkBody("{\"Tracking\": {\"ClickTrackingEnabled\": \"on\"}}", recipient)).statusCode(400)
                .body("message", equalTo("1 validation error detected: " + CLICK_ENUM));
        assertNothingSentTo(recipient);
    }

    private static ValidatableResponse sendWith(String overrides) {
        return sendWith(overrides, "alice@example.com");
    }

    private static ValidatableResponse sendWith(String overrides, String recipient) {
        return send(SEND, "{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": {\"ToAddresses\": "
                + "[\"" + recipient + "\"]}, " + SIMPLE + ", \"ConfigurationOverrides\": " + overrides + "}");
    }

    private static String bulkBody(String overrides) {
        return bulkBody(overrides, "bob@example.com");
    }

    private static String bulkBody(String overrides, String secondRecipient) {
        return "{\"FromEmailAddress\": \"" + SENDER + "\", \"DefaultContent\": {\"Template\": "
                + "{\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"{}\"}}, "
                + "\"BulkEmailEntries\": [{\"Destination\": {\"ToAddresses\": [\"alice@example.com\"]}}, "
                + "{\"Destination\": {\"ToAddresses\": [\"" + secondRecipient + "\"]}}], "
                + "\"ConfigurationOverrides\": " + overrides + "}";
    }

    // The check runs before the send reaches the service, so a refused request records nothing.
    private static void assertNothingSentTo(String recipient) {
        assertTrue(SesMailbox.messagesTo(recipient).isEmpty(), "a refused send recorded a message to " + recipient);
    }

    private static ValidatableResponse send(String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
                .when().post(path).then();
    }
}
