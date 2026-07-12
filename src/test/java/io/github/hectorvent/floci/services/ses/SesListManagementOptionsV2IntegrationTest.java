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
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("amazonSESUnsubscribeUrl")));
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
    @Order(99)
    void cleanup_deleteContactList() {
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/contact-lists/" + LIST).then().statusCode(200);
    }
}
