package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * A null address element passes deserialization on AWS and fails later (probe-confirmed): on
 * SendEmail after the content and sender checks and before the tenant lookup, on SendBulkEmail as
 * the status of the entries it affects.
 */
@QuarkusTest
class SesSendNullAddressIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String SENDER = "sender@example.com";
    private static final String TO = "success@simulator.amazonses.com";
    private static final String SIMPLE =
            "{\"Simple\": {\"Subject\": {\"Data\": \"s\"}, \"Body\": {\"Text\": {\"Data\": \"b\"}}}}";
    private static final String INLINE_TEMPLATE =
            "{\"Template\": {\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"{}\"}}";
    private static final String NULL_ADDRESS = "Invalid email address null.";
    private static final String EMPTY_DESTINATION = "Destination cannot be empty";

    private static ValidatableResponse post(String path, String body) {
        return given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post(path)
        .then();
    }

    private static ValidatableResponse send(String body) {
        return post("/v2/email/outbound-emails", body);
    }

    private static ValidatableResponse bulk(String members, String entries) {
        return post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + SENDER + "\", " + members
                + "\"DefaultContent\": " + INLINE_TEMPLATE + ", \"BulkEmailEntries\": [" + entries + "]}");
    }

    private static String entry(String destination) {
        return "{\"Destination\": " + destination + "}";
    }

    @Test
    void sendEmail_nullToAddress_isAnInvalidAddress() {
        send("{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": {\"ToAddresses\": [null]}, "
                + "\"Content\": " + SIMPLE + "}")
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(NULL_ADDRESS));
    }

    @Test
    void sendEmail_missingContentPrecedesNullAddress() {
        send("{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, "
                + "\"ReplyToAddresses\": [null]}")
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Content must contain Raw, Simple, or Template."));
    }

    @Test
    void sendEmail_missingSenderPrecedesNullAddress() {
        send("{\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"ReplyToAddresses\": [null], "
                + "\"Content\": " + SIMPLE + "}")
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", nullValue());
    }

    @Test
    void sendEmail_nullAddressPrecedesTenantLookup() {
        send("{\"FromEmailAddress\": \"" + SENDER + "\", \"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, "
                + "\"ReplyToAddresses\": [null], \"TenantName\": \"no-such-tenant\", \"Content\": " + SIMPLE + "}")
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(NULL_ADDRESS));
    }

    @Test
    void sendBulkEmail_onlyNullRecipientsInTheRequest_answerEmptyDestination() {
        bulk("", entry("{\"ToAddresses\": [null]}") + ", " + entry("{\"ToAddresses\": [null]}"))
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo(EMPTY_DESTINATION))
            .body("BulkEmailEntryResults[1].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[1].Error", equalTo(EMPTY_DESTINATION));
    }

    @Test
    void sendBulkEmail_nullRecipientBesideAnotherEntrysRecipient_isAnInvalidAddress() {
        bulk("", entry("{\"ToAddresses\": [null]}") + ", " + entry("{\"ToAddresses\": [\"" + TO + "\"]}"))
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo(NULL_ADDRESS));
    }

    @Test
    void sendBulkEmail_nullRecipientBesideARecipient_isAnInvalidAddress() {
        bulk("", entry("{\"ToAddresses\": [null, \"" + TO + "\"]}"))
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo(NULL_ADDRESS));
    }

    @Test
    void sendBulkEmail_nullCcAddress_isAnInvalidAddress() {
        bulk("", entry("{\"ToAddresses\": [\"" + TO + "\"], \"CcAddresses\": [null]}"))
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo(NULL_ADDRESS));
    }

    @Test
    void sendBulkEmail_nullReplyToAddress_failsEveryEntry() {
        bulk("\"ReplyToAddresses\": [null], ", entry("{\"ToAddresses\": [\"" + TO + "\"]}") + ", "
                + entry("{\"ToAddresses\": [\"" + TO + "\"]}"))
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo(NULL_ADDRESS))
            .body("BulkEmailEntryResults[1].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[1].Error", equalTo(NULL_ADDRESS));
    }
}
