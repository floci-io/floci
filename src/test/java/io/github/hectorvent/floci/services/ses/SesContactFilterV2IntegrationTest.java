package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * ListContacts {@code Filter} on the wire: parsing, the SES v2 boolean coercion of
 * UseDefaultIfPreferenceUnavailable, exact filtered pages and the error statuses. The membership
 * rules and the full error precedence are covered by {@code SesContactFilterServiceTest}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SesContactFilterV2IntegrationTest {

    private static final String REGION = "me-central-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String LIST = "filter-wire";
    private static final String CONTACTS = "/v2/email/contact-lists/" + LIST + "/contacts";

    private boolean created;

    // Not @BeforeAll: the Quarkus test port is only bound once the first test starts.
    @BeforeEach
    void createContacts() {
        if (created) {
            return;
        }
        send("/v2/email/contact-lists", """
                {"ContactListName": "%s", "Topics": [
                  {"TopicName": "news", "DisplayName": "News", "DefaultSubscriptionStatus": "OPT_IN"}]}
                """.formatted(LIST)).statusCode(200);
        // a and c opted out of news explicitly, b and d have no preference, e unsubscribed from all.
        contact("a", "{\"TopicName\":\"news\",\"SubscriptionStatus\":\"OPT_OUT\"}", false);
        contact("b", "", false);
        contact("c", "{\"TopicName\":\"news\",\"SubscriptionStatus\":\"OPT_OUT\"}", false);
        contact("d", "", false);
        contact("e", "", true);
        created = true;
    }

    @Test
    void topicFilter_withDefault_pagesTheFilteredSetExactly() {
        String filter = "\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": "
                + "{\"TopicName\": \"news\", \"UseDefaultIfPreferenceUnavailable\": \"yes\"}}";
        String token = list("{" + filter + ", \"PageSize\": 1}")
                .body("Contacts.EmailAddress", contains("b@example.com"))
                .extract().path("NextToken");
        list("{" + filter + ", \"PageSize\": 1, \"NextToken\": \"" + token + "\"}")
                .body("Contacts.EmailAddress", contains("d@example.com"))
                .body("NextToken", nullValue());
    }

    @Test
    void topicFilter_withoutDefault_skipsContactsWithoutAPreference() {
        list("{\"Filter\": {\"FilteredStatus\": \"OPT_OUT\", \"TopicFilter\": {\"TopicName\": \"news\"}}}")
                .body("Contacts.EmailAddress", contains("a@example.com", "c@example.com", "e@example.com"));
    }

    @Test
    void filteredStatusAlone_selectsUnsubscribeAll() {
        list("{\"Filter\": {\"FilteredStatus\": \"OPT_OUT\"}}")
                .body("Contacts.EmailAddress", contains("e@example.com"));
    }

    @Test
    void useDefault_rejectsAStringThatIsNotABoolean() {
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": "
                + "{\"TopicName\": \"news\", \"UseDefaultIfPreferenceUnavailable\": \"abc\"}}}")
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("STRING_VALUE can not be converted to an Boolean"));
    }

    @Test
    void errors_carryTheProbedStatusesAndMessages() {
        send(CONTACTS + "/list", "{\"Filter\": {}}").statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid FilteredStatus <null>"));
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": {}}}")
                .statusCode(400)
                .body("message", equalTo("TopicName can't be blank in TopicFilter."));
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": "
                + "{\"TopicName\": \"nope\"}}}")
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("List: " + LIST + " doesn't contain Topic: nope"));
    }

    @Test
    void wrongJsonTypes_areSerializationExceptions() {
        send(CONTACTS + "/list", "{\"Filter\": []}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Start of list found where not expected"));
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": \"x\"}}")
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Expected null"));
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": 1}, \"PageSize\": 0}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a String"));
    }

    @Test
    void topicName_isMatchedExactly() {
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": "
                + "{\"TopicName\": \" news \"}}}")
                .statusCode(404)
                .body("message", equalTo("List: " + LIST + " doesn't contain Topic:  news "));
        send(CONTACTS + "/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": "
                + "{\"TopicName\": \" \"}}}")
                .statusCode(400)
                .body("message", equalTo("TopicName can't be blank in TopicFilter."));
    }

    @Test
    void nullTopicFilter_isTreatedAsAbsent() {
        list("{\"Filter\": {\"FilteredStatus\": \"OPT_OUT\", \"TopicFilter\": null}}")
                .body("Contacts.EmailAddress", contains("e@example.com"));
    }

    @Test
    void filterOnAMissingList_reportsTheListBeforeTheTopic() {
        send("/v2/email/contact-lists/ghost/contacts/list", "{\"Filter\": {\"FilteredStatus\": \"OPT_IN\", "
                + "\"TopicFilter\": {\"TopicName\": \"nope\"}}}")
                .statusCode(404)
                .body("message", equalTo("List with name: ghost doesn't exist."));
    }

    private void contact(String name, String preference, boolean unsubscribeAll) {
        send(CONTACTS, """
                {"EmailAddress": "%s@example.com", "TopicPreferences": [%s], "UnsubscribeAll": %s}
                """.formatted(name, preference, unsubscribeAll)).statusCode(200);
    }

    private static ValidatableResponse list(String body) {
        return send(CONTACTS + "/list", body).statusCode(200);
    }

    private static ValidatableResponse send(String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
                .when().post(path).then();
    }
}
