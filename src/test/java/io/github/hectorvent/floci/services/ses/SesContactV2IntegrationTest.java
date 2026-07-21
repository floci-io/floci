package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration test for SES V2 Contact CRUD under
 * {@code /v2/email/contact-lists/{name}/contacts}. Behaviour and error strings
 * are verified against real AWS (TopicDefaultPreferences derivation, validation
 * order, update merge/replace semantics, and the CRUD error shapes).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesContactV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String LIST = "contacts-it-list";
    private static final String EMAIL = "alice@example.com";
    private static final String CONTACTS = "/v2/email/contact-lists/" + LIST + "/contacts";

    private static io.restassured.response.Response post(String path, String body) {
        return given().contentType("application/json").header("Authorization", SES_AUTH)
                .body(body).when().post(path);
    }

    @Test
    @Order(1)
    void setup_createContactList() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"" + LIST + "\",\"Topics\":["
                        + "{\"TopicName\":\"weekly\",\"DisplayName\":\"Weekly\",\"DefaultSubscriptionStatus\":\"OPT_IN\"},"
                        + "{\"TopicName\":\"promos\",\"DisplayName\":\"Promos\",\"DefaultSubscriptionStatus\":\"OPT_OUT\"}]}")
        .when().post("/v2/email/contact-lists").then().statusCode(200);
    }

    @Test
    @Order(2)
    void createContact() {
        post(CONTACTS, "{\"EmailAddress\":\"" + EMAIL + "\",\"TopicPreferences\":["
                + "{\"TopicName\":\"weekly\",\"SubscriptionStatus\":\"OPT_OUT\"}],"
                + "\"AttributesData\":\"{\\\"name\\\":\\\"Alice\\\"}\",\"UnsubscribeAll\":false}")
        .then().statusCode(200);
    }

    @Test
    @Order(3)
    void getContact_shape() {
        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/" + EMAIL)
        .then().statusCode(200)
                .body("EmailAddress", equalTo(EMAIL))
                .body("TopicPreferences", hasSize(1))
                .body("TopicPreferences[0].TopicName", equalTo("weekly"))
                .body("TopicPreferences[0].SubscriptionStatus", equalTo("OPT_OUT"))
                // promos not set by the contact -> surfaced via TopicDefaultPreferences (list default).
                .body("TopicDefaultPreferences", hasSize(1))
                .body("TopicDefaultPreferences[0].TopicName", equalTo("promos"))
                .body("TopicDefaultPreferences[0].SubscriptionStatus", equalTo("OPT_OUT"))
                .body("UnsubscribeAll", equalTo(false))
                .body("AttributesData", equalTo("{\"name\":\"Alice\"}"))
                .body("CreatedTimestamp", notNullValue())
                .body("LastUpdatedTimestamp", notNullValue());
    }

    @Test
    @Order(4)
    void createContact_duplicate_returnsAlreadyExists() {
        post(CONTACTS, "{\"EmailAddress\":\"" + EMAIL + "\"}")
        .then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                .body("message", equalTo(EMAIL + " already exists in List."));
    }

    @Test
    @Order(5)
    void getContact_unknownEmail_returns404() {
        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/ghost@example.com")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("ghost@example.com doesn't exist in List."));
    }

    @Test
    @Order(6)
    void getContact_unknownList_returns404() {
        given().header("Authorization", SES_AUTH)
        .when().get("/v2/email/contact-lists/does-not-exist/contacts/" + EMAIL)
        .then().statusCode(404)
                .body("message", equalTo("List with name: does-not-exist doesn't exist."));
    }

    @Test
    @Order(7)
    void createContact_unknownTopic_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"bob@example.com\",\"TopicPreferences\":["
                + "{\"TopicName\":\"nope\",\"SubscriptionStatus\":\"OPT_IN\"}]}")
        .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("List: " + LIST + " doesn't contain Topic: nope"));
    }

    @Test
    @Order(8)
    void createContact_invalidSubscriptionStatus_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"bob@example.com\",\"TopicPreferences\":["
                + "{\"TopicName\":\"weekly\",\"SubscriptionStatus\":\"MAYBE\"}]}")
        .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topicPreferences.1.member.subscriptionStatus' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OPT_OUT, OPT_IN]"));
    }

    @Test
    @Order(9)
    void createContact_invalidEmail_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"not-an-email\"}")
        .then().statusCode(400)
                .body("message", equalTo("EmailAddress <not-an-email> is invalid"));
    }

    @Test
    @Order(10)
    void createContact_invalidEmailAndInvalidEnum_reportsEnumFirst() {
        // Probe-verified: Smithy enum validation precedes the EmailAddress format check.
        post(CONTACTS, "{\"EmailAddress\":\"not-an-email\",\"TopicPreferences\":["
                + "{\"TopicName\":\"weekly\",\"SubscriptionStatus\":\"MAYBE\"}]}")
        .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topicPreferences.1.member.subscriptionStatus' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OPT_OUT, OPT_IN]"));
    }

    @Test
    @Order(11)
    void updateContact_unsubscribeAll_replacesAttributesKeepsPreferences() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"UnsubscribeAll\":true}")
        .when().put(CONTACTS + "/" + EMAIL).then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/" + EMAIL)
        .then().statusCode(200)
                .body("UnsubscribeAll", equalTo(true))
                // TopicPreferences kept; AttributesData cleared (replace semantics).
                .body("TopicPreferences", hasSize(1))
                .body("TopicPreferences[0].TopicName", equalTo("weekly"))
                .body("AttributesData", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @Order(12)
    void updateContact_topicPreferences_mergeByTopicName() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"TopicPreferences\":[{\"TopicName\":\"promos\",\"SubscriptionStatus\":\"OPT_IN\"}]}")
        .when().put(CONTACTS + "/" + EMAIL).then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/" + EMAIL)
        .then().statusCode(200)
                // Merged: existing weekly kept + promos added; no topic left for defaults.
                .body("TopicPreferences", hasSize(2))
                .body("TopicDefaultPreferences", hasSize(0));
    }

    @Test
    @Order(13)
    void listContacts_lightweightProjection() {
        given().contentType("application/json").header("Authorization", SES_AUTH).body("{}")
        .when().post(CONTACTS + "/list")
        .then().statusCode(200)
                .body("Contacts", hasSize(1))
                .body("Contacts[0].EmailAddress", equalTo(EMAIL))
                .body("Contacts[0].TopicPreferences", hasSize(2))
                .body("Contacts[0].LastUpdatedTimestamp", notNullValue())
                // ListContacts is a lightweight projection: no AttributesData / CreatedTimestamp.
                .body("Contacts[0].AttributesData", org.hamcrest.Matchers.nullValue())
                .body("Contacts[0].CreatedTimestamp", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @Order(14)
    void deleteContact_thenGone() {
        given().header("Authorization", SES_AUTH)
        .when().delete(CONTACTS + "/" + EMAIL).then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/" + EMAIL).then().statusCode(404);
    }

    @Test
    @Order(15)
    void deleteContact_unknown_returns404() {
        given().header("Authorization", SES_AUTH)
        .when().delete(CONTACTS + "/ghost@example.com")
        .then().statusCode(404)
                .body("message", equalTo("ghost@example.com doesn't exist in List."));
    }

    @Test
    @Order(16)
    void deleteContactList_purgesContacts_noLeakOnRecreate() {
        // Add a contact, delete the list (which must cascade-delete its contacts), then recreate a
        // same-named list — the old contact must not leak into it.
        post(CONTACTS, "{\"EmailAddress\":\"carol@example.com\"}").then().statusCode(200);
        given().header("Authorization", SES_AUTH)
        .when().delete("/v2/email/contact-lists/" + LIST).then().statusCode(200);
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"" + LIST + "\",\"Topics\":["
                        + "{\"TopicName\":\"weekly\",\"DisplayName\":\"Weekly\","
                        + "\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .when().post("/v2/email/contact-lists").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH).body("{}")
        .when().post(CONTACTS + "/list")
        .then().statusCode(200).body("Contacts", hasSize(0));
    }

    @Test
    @Order(17)
    void getContact_invalidEmail_returns400() {
        // Verified against real AWS: the EmailAddress format is validated before list existence.
        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/not-an-email")
        .then().statusCode(400)
                .body("message", equalTo("EmailAddress <not-an-email> is invalid"));
    }

    @Test
    @Order(18)
    void getContact_invalidEmailAndUnknownList_reportsEmailFirst() {
        given().header("Authorization", SES_AUTH)
        .when().get("/v2/email/contact-lists/does-not-exist/contacts/not-an-email")
        .then().statusCode(400)
                .body("message", equalTo("EmailAddress <not-an-email> is invalid"));
    }

    @Test
    @Order(19)
    void deleteContact_invalidEmail_returns400() {
        given().header("Authorization", SES_AUTH)
        .when().delete(CONTACTS + "/not-an-email")
        .then().statusCode(400)
                .body("message", equalTo("EmailAddress <not-an-email> is invalid"));
    }

    @Test
    @Order(20)
    void listContacts_nonObjectBody_returns400() {
        given().contentType("application/json").header("Authorization", SES_AUTH).body("[]")
        .when().post(CONTACTS + "/list")
        .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    // Type coercion — verified against real AWS (Jackson-backed), consistent with parseSendingEnabled:
    // a JSON string coerces to the Boolean UnsubscribeAll (any string -> true), but a number is a
    // SerializationException; AttributesData (String) rejects non-string types the same way.

    @Test
    @Order(21)
    void createContact_unsubscribeAllNumber_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"type-a@example.com\",\"UnsubscribeAll\":1}")
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a Boolean"));
    }

    @Test
    @Order(22)
    void createContact_attributesDataNumber_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"type-b@example.com\",\"AttributesData\":123}")
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a String"));
    }

    @Test
    @Order(23)
    void createContact_attributesDataBoolean_returns400() {
        post(CONTACTS, "{\"EmailAddress\":\"type-c@example.com\",\"AttributesData\":true}")
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("TRUE_VALUE can not be converted to a String"));
    }

    @Test
    @Order(24)
    void createContact_unsubscribeAllString_coercesToTrue() {
        // AWS coerces ANY JSON string to true for the Boolean field (unlike a number, which is rejected).
        post(CONTACTS, "{\"EmailAddress\":\"type-d@example.com\",\"UnsubscribeAll\":\"no\"}")
        .then().statusCode(200);
        given().header("Authorization", SES_AUTH)
        .when().get(CONTACTS + "/type-d@example.com")
        .then().statusCode(200).body("UnsubscribeAll", equalTo(true));
    }

    @Test
    @Order(25)
    void updateContact_attributesDataNumber_returns400() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"AttributesData\":123}")
        .when().put(CONTACTS + "/" + EMAIL)
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a String"));
    }

    // EmailAddress validation — verified against real AWS: missing/null is a Smithy validation
    // error, "" is "can't be blank", and only a non-blank malformed value is "invalid".

    @Test
    @Order(26)
    void createContact_missingEmail_returnsValidationError() {
        post(CONTACTS, "{\"UnsubscribeAll\":false}")
        .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'emailAddress' "
                        + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(27)
    void createContact_nullEmail_returnsValidationError() {
        post(CONTACTS, "{\"EmailAddress\":null}")
        .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'emailAddress' "
                        + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(28)
    void createContact_blankEmail_returnsCantBeBlank() {
        post(CONTACTS, "{\"EmailAddress\":\"\"}")
        .then().statusCode(400)
                .body("message", equalTo("EmailAddress can't be blank."));
    }

    // Runs last: it deletes LIST, so no later ordered test may depend on it.
    @Test
    @Order(30)
    void createOrUpdateContact_intoDeletedList_returns404() {
        // Create/update re-check the list under the mutation lock, so a write can't land in a list
        // that has been deleted (which would orphan the contact for a same-named list recreated later).
        // AWS allows only one contact list per account, so this reuses (and deletes) LIST.
        post(CONTACTS, "{\"EmailAddress\":\"bob@example.com\"}").then().statusCode(200);
        given().header("Authorization", SES_AUTH)
        .when().delete("/v2/email/contact-lists/" + LIST).then().statusCode(200);

        post(CONTACTS, "{\"EmailAddress\":\"ghost@example.com\"}")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"UnsubscribeAll\":true}")
        .when().put(CONTACTS + "/bob@example.com")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
