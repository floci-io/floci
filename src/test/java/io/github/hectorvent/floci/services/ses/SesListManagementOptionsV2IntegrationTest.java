package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for the SES V2 {@code SendEmail} {@code ListManagementOptions} wiring through the
 * REST controller: an absent recipient is auto-created as a contact, an unknown contact list fails
 * the send, and a malformed {@code ListManagementOptions} is rejected. Uses an isolated region so the
 * one-contact-list-per-account limit does not collide with other SES tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesListManagementOptionsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/ses/aws4_request";
    private static final String LIST = "lmo-newsletter";
    private static final String FROM = "lmo-sender@floci.test";

    @Test
    @Order(0)
    void setup_contactList() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "ContactListName": "%s",
                      "Topics": [
                        {"TopicName": "Sports", "DisplayName": "Sports",
                         "DefaultSubscriptionStatus": "OPT_IN", "Description": "d"}
                      ]
                    }
                    """.formatted(LIST))
        .when().post("/v2/email/contact-lists").then().statusCode(200);
    }

    @Test
    @Order(1)
    void sendWithListManagementOptions_autoCreatesAbsentRecipient() {
        String recipient = "lmo-newcontact@floci.test";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["%s"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}}}},
                      "ListManagementOptions": {"ContactListName": "%s"}
                    }
                    """.formatted(FROM, recipient, LIST))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        // AWS auto-creates a contact on the list for a recipient that isn't one yet.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/contact-lists/" + LIST + "/contacts/" + recipient)
        .then().statusCode(200)
                .body("EmailAddress", equalTo(recipient));
    }

    @Test
    @Order(2)
    void sendWithUnknownContactList_returns404() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["lmo-x@floci.test"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}}}},
                      "ListManagementOptions": {"ContactListName": "lmo-ghost-list"}
                    }
                    """.formatted(FROM))
        .when().post("/v2/email/outbound-emails").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(3)
    void listManagementOptionsWithoutContactListName_returns400() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["lmo-x@floci.test"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}}}},
                      "ListManagementOptions": {"TopicName": "Sports"}
                    }
                    """.formatted(FROM))
        .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void listManagementOptionsWithNonStringTopicName_returns400() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["lmo-x@floci.test"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}}}},
                      "ListManagementOptions": {"ContactListName": "%s", "TopicName": 123}
                    }
                    """.formatted(FROM, LIST))
        .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(5)
    void singleRecipientSend_replacesUnsubscribePlaceholderWithFunctionalUrl() {
        String recipient = "lmo-unsub-body@floci.test";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["%s"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"},
                        "Body": {"Html": {"Data": "<p>Unsub: {{amazonSESUnsubscribeUrl}}</p>"}}}},
                      "ListManagementOptions": {"ContactListName": "%s", "TopicName": "Sports"}
                    }
                    """.formatted(FROM, recipient, LIST))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        // The stored message body has the placeholder replaced with a functional Floci unsubscribe URL.
        given().header("Authorization", AUTH).queryParam("email", recipient)
        .when().get("/_aws/ses").then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("/_aws/ses/unsubscribe?"))
                .body(org.hamcrest.Matchers.containsString("contactList=" + LIST))
                .body(org.hamcrest.Matchers.containsString("topic=Sports"))
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("amazonSESUnsubscribeUrl")))
                // the stored message also carries the List-Unsubscribe header
                .body(org.hamcrest.Matchers.containsString("List-Unsubscribe"));
    }

    @Test
    @Order(6)
    void unsubscribeEndpoint_optsContactOutOfTopic() {
        String recipient = "lmo-endpoint@floci.test";
        given().header("Authorization", AUTH)
                .queryParam("region", "us-west-2").queryParam("contactList", LIST)
                .queryParam("topic", "Sports").queryParam("address", recipient)
        .when().post("/_aws/ses/unsubscribe").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/contact-lists/" + LIST + "/contacts/" + recipient)
        .then().statusCode(200)
                .body("TopicPreferences.find { it.TopicName == 'Sports' }.SubscriptionStatus",
                        equalTo("OPT_OUT"));
    }

    @Test
    @Order(7)
    void unsubscribeEndpoint_missingRegion_returns400() {
        given().header("Authorization", AUTH)
                .queryParam("contactList", LIST).queryParam("address", "lmo-x@floci.test")
        .when().post("/_aws/ses/unsubscribe").then().statusCode(400);
    }

    @Test
    @Order(8)
    void unsubscribeEndpoint_get_rendersConfirmationWithoutOptingOut() {
        String recipient = "lmo-get@floci.test";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + recipient + "\",\"TopicPreferences\":["
                        + "{\"TopicName\":\"Sports\",\"SubscriptionStatus\":\"OPT_IN\"}]}")
        .when().post("/v2/email/contact-lists/" + LIST + "/contacts").then().statusCode(200);

        // A browser click (GET) only renders a confirmation form; it must not opt the contact out,
        // so a client or bot that prefetches the link cannot silently unsubscribe the contact.
        given().header("Authorization", AUTH)
                .queryParam("region", "us-west-2").queryParam("contactList", LIST)
                .queryParam("topic", "Sports").queryParam("address", recipient)
        .when().get("/_aws/ses/unsubscribe").then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("<form method=\"post\""))
                .body(org.hamcrest.Matchers.containsString("Unsubscribe"))
                // The form action escapes '&' as '&amp;' for valid HTML, and carries the RFC 8058
                // one-click parameter so its POST body matches the advertised header.
                .body(org.hamcrest.Matchers.containsString("&amp;contactList="))
                .body(org.hamcrest.Matchers.containsString(
                        "<input type=\"hidden\" name=\"List-Unsubscribe\" value=\"One-Click\">"));

        given().header("Authorization", AUTH)
        .when().get("/v2/email/contact-lists/" + LIST + "/contacts/" + recipient)
        .then().statusCode(200)
                .body("TopicPreferences.find { it.TopicName == 'Sports' }.SubscriptionStatus",
                        equalTo("OPT_IN"));
    }

    @Test
    @Order(9)
    void send_unsafeHeader_isDroppedFromStoredMessage() {
        String recipient = "lmo-unsafe-hdr@floci.test";
        // A user-supplied header whose value carries CR/LF must not survive on any surface: the SMTP
        // relay already drops it, and the stored SentEmail (GET /_aws/ses) must not retain it either.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["%s"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "hi"}},
                        "Headers": [
                          {"Name": "X-Safe", "Value": "ok"},
                          {"Name": "X-Evil", "Value": "bad\\r\\nBcc: attacker@evil.com"}
                        ]}}
                    }
                    """.formatted(FROM, recipient))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        given().header("Authorization", AUTH).queryParam("email", recipient)
        .when().get("/_aws/ses").then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("X-Safe"))
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("X-Evil")))
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("attacker@evil.com")));
    }

    @Test
    @Order(10)
    void send_headerMissingValue_isRejectedLikeAws() {
        // AWS enforces Content.Simple.Headers[].Value as a required member: a header object that omits
        // Value is rejected with BadRequestException and a Smithy constraint message, rather than being
        // silently dropped.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["lmo-x@floci.test"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}},
                        "Headers": [{"Name": "X-Foo"}]}}
                    }
                    """.formatted(FROM))
        .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", org.hamcrest.Matchers.containsString(
                        "content.simple.headers.1.member.value"))
                .body("message", org.hamcrest.Matchers.containsString("Member must not be null"));
    }

    @Test
    @Order(11)
    void send_headerMissingName_isRejectedLikeAws() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["lmo-x@floci.test"]},
                      "Content": {"Simple": {"Subject": {"Data": "hi"}, "Body": {"Text": {"Data": "b"}},
                        "Headers": [{"Value": "bar"}]}}
                    }
                    """.formatted(FROM))
        .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", org.hamcrest.Matchers.containsString(
                        "content.simple.headers.1.member.name"))
                .body("message", org.hamcrest.Matchers.containsString("Member must not be null"));
    }

    @Test
    @Order(99)
    void cleanup_deleteContactList() {
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/contact-lists/" + LIST).then().statusCode(200);
    }
}
