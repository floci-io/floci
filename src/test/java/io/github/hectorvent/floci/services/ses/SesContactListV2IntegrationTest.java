package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration test for SES V2 ContactList CRUD:
 *   POST/GET/PUT/DELETE /v2/email/contact-lists[/{name}].
 * Verifies the AWS-confirmed "at most one contact list per account per region"
 * limit, topic validation, and the GetContactList response shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesContactListV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String LIST = "my-newsletter";

    private static final String CREATE_BODY = "{"
            + "\"ContactListName\":\"" + LIST + "\","
            + "\"Description\":\"newsletter\","
            + "\"Topics\":["
            + "{\"TopicName\":\"weekly\",\"DisplayName\":\"Weekly\","
            + "\"DefaultSubscriptionStatus\":\"OPT_IN\",\"Description\":\"weekly digest\"},"
            + "{\"TopicName\":\"promos\",\"DisplayName\":\"Promotions\","
            + "\"DefaultSubscriptionStatus\":\"OPT_OUT\"}"
            + "]}";

    @Test
    @Order(1)
    void createContactList() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body(CREATE_BODY)
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void getContactList_returnsShape() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200)
                .body("ContactListName", equalTo(LIST))
                .body("Description", equalTo("newsletter"))
                .body("Topics", hasSize(2))
                .body("Topics[0].TopicName", equalTo("weekly"))
                .body("Topics[0].DefaultSubscriptionStatus", equalTo("OPT_IN"))
                .body("Topics[1].TopicName", equalTo("promos"))
                .body("CreatedTimestamp", notNullValue())
                .body("LastUpdatedTimestamp", notNullValue());
    }

    @Test
    @Order(3)
    void listContactLists_containsList() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists")
        .then()
                .statusCode(200)
                .body("ContactLists.ContactListName", hasItem(LIST));
    }

    @Test
    @Order(4)
    void createSecondContactList_rejectedByPerAccountLimit() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"second-list\"}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("A maximum of 1 Lists allowed per account."));
    }

    @Test
    @Order(5)
    void createDuplicateName_alsoHitsPerAccountLimit() {
        // A duplicate name hits the one-list-per-account limit before any "already exists" check,
        // so AWS never returns AlreadyExistsException here.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"" + LIST + "\"}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("A maximum of 1 Lists allowed per account."));
    }

    @Test
    @Order(6)
    void createContactList_invalidTopicStatus_returns400() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"bad-list\",\"Topics\":["
                        + "{\"TopicName\":\"t\",\"DisplayName\":\"T\","
                        + "\"DefaultSubscriptionStatus\":\"MAYBE\"}]}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topics.1.member.defaultSubscriptionStatus' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OPT_OUT, OPT_IN]"));
    }

    @Test
    @Order(7)
    void updateContactList_replacesTopicsAndDescription() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"Description\":\"updated\",\"Topics\":["
                        + "{\"TopicName\":\"monthly\",\"DisplayName\":\"Monthly\","
                        + "\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .when()
                .put("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200)
                .body("Description", equalTo("updated"))
                .body("Topics", hasSize(1))
                .body("Topics[0].TopicName", equalTo("monthly"));

        // An explicit-null Topics is treated as "not provided" and preserves existing topics.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"Topics\":null,\"Description\":\"kept-topics\"}")
        .when()
                .put("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200)
                .body("Description", equalTo("kept-topics"))
                .body("Topics", hasSize(1))
                .body("Topics[0].TopicName", equalTo("monthly"));

        // An empty body is accepted as a no-op update (200), matching real AWS.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
        .when()
                .put("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200);
    }

    @Test
    @Order(8)
    void getContactList_unknown_returns404() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/does-not-exist")
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("List with name: does-not-exist doesn't exist."));
    }

    @Test
    @Order(9)
    void updateContactList_unknown_returns404() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"Description\":\"x\"}")
        .when()
                .put("/v2/email/contact-lists/does-not-exist")
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(10)
    void deleteContactList_thenGoneAndSlotFreed() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .delete("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/" + LIST)
        .then()
                .statusCode(404);

        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists")
        .then()
                .statusCode(200)
                .body("ContactLists", hasSize(0));

        // The single-list slot is freed, so a new list can be created.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"fresh-list\"}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", SES_AUTH)
        .when()
                .delete("/v2/email/contact-lists/fresh-list")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(11)
    void createContactList_missingName_returnsSmithyValidationError() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'contactListName' "
                        + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(12)
    void createContactList_topicMissingTopicName_returnsSmithyValidationError() {
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"ContactListName\":\"x\",\"Topics\":["
                        + "{\"DisplayName\":\"T\",\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .when()
                .post("/v2/email/contact-lists")
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topics.1.member.topicName' failed to satisfy constraint: "
                        + "Member must not be null"));
    }

    @Test
    @Order(13)
    void createContactList_tooManyTopics_returns400() {
        post(create("many-topics", topics(21)))
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Maximum of <20> topics allowed per ContactList"));
    }

    @Test
    @Order(14)
    void createContactList_duplicateTopicNames_returns400() {
        post("{\"ContactListName\":\"dup\",\"Topics\":["
                + "{\"TopicName\":\"x\",\"DisplayName\":\"A\",\"DefaultSubscriptionStatus\":\"OPT_IN\"},"
                + "{\"TopicName\":\"x\",\"DisplayName\":\"B\",\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .then()
                .statusCode(400)
                .body("message", equalTo("Duplicate topic names are not allowed within a List."));
    }

    @Test
    @Order(15)
    void createContactList_invalidNameChars_returns400() {
        post("{\"ContactListName\":\"bad name!\"}")
        .then()
                .statusCode(400)
                .body("message", equalTo("ContactListName can contain up to 64 characters. "
                        + "Only alphanumeric characters, underscores(_) and hyphens(-) are allowed."));
    }

    @Test
    @Order(16)
    void createContactList_blankName_returns400() {
        post("{\"ContactListName\":\"\"}")
        .then()
                .statusCode(400)
                .body("message", equalTo("ContactListName can't be blank."));
    }

    @Test
    @Order(17)
    void createContactList_blankTopicName_returns400() {
        post("{\"ContactListName\":\"ok\",\"Topics\":["
                + "{\"TopicName\":\"\",\"DisplayName\":\"A\",\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .then()
                .statusCode(400)
                .body("message", equalTo("TopicName can't be blank."));
    }

    @Test
    @Order(18)
    void createContactList_displayNameTooLong_returns400() {
        post("{\"ContactListName\":\"ok\",\"Topics\":["
                + "{\"TopicName\":\"t\",\"DisplayName\":\"" + "a".repeat(129)
                + "\",\"DefaultSubscriptionStatus\":\"OPT_IN\"}]}")
        .then()
                .statusCode(400)
                .body("message", equalTo("Topic DisplayName can contain up to <128> characters."));
    }

    @Test
    @Order(19)
    void createContactList_descriptionTooLong_returns400() {
        post("{\"ContactListName\":\"ok\",\"Description\":\"" + "a".repeat(501) + "\"}")
        .then()
                .statusCode(400)
                .body("message", equalTo("List description can contain up to 500 characters."));
    }

    @Test
    @Order(20)
    void getContactList_invalidName_returns400NotNotFound() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .get("/v2/email/contact-lists/" + "a".repeat(65))
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("ContactListName can contain up to 64 characters. "
                        + "Only alphanumeric characters, underscores(_) and hyphens(-) are allowed."));
    }

    @Test
    @Order(21)
    void deleteContactList_invalidName_returns400NotNotFound() {
        given()
                .header("Authorization", SES_AUTH)
        .when()
                .delete("/v2/email/contact-lists/" + "a".repeat(65))
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(22)
    void createContactList_invalidNameAndInvalidTopicEnum_reportsSmithyEnumFirst() {
        // Probe-verified two-phase order: protocol-layer (Smithy) enum validation precedes the
        // ContactListName constraint, so the enum error wins even though the name is also invalid.
        post("{\"ContactListName\":\"bad name!\",\"Topics\":["
                + "{\"TopicName\":\"t\",\"DisplayName\":\"T\",\"DefaultSubscriptionStatus\":\"MAYBE\"}]}")
        .then()
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topics.1.member.defaultSubscriptionStatus' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OPT_OUT, OPT_IN]"));
    }

    @Test
    @Order(23)
    void createContactList_invalidNameAndTooManyTopics_reportsNameFirst() {
        // Probe-verified: among service-level constraints, ContactListName is validated before the
        // topic-count limit.
        post(create("bad name!", topics(21)))
        .then()
                .statusCode(400)
                .body("message", equalTo("ContactListName can contain up to 64 characters. "
                        + "Only alphanumeric characters, underscores(_) and hyphens(-) are allowed."));
    }

    @Test
    @Order(24)
    void updateContactList_invalidNameAndInvalidTopicEnum_reportsSmithyEnumFirst() {
        // Same two-phase order on update: the Smithy enum error wins over the invalid path name.
        given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body("{\"Topics\":[{\"TopicName\":\"t\",\"DisplayName\":\"T\","
                        + "\"DefaultSubscriptionStatus\":\"MAYBE\"}]}")
        .when()
                .put("/v2/email/contact-lists/" + "a".repeat(65))
        .then()
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'topics.1.member.defaultSubscriptionStatus' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OPT_OUT, OPT_IN]"));
    }

    private static io.restassured.response.Response post(String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", SES_AUTH)
                .body(body)
        .when()
                .post("/v2/email/contact-lists");
    }

    private static String create(String name, String topicsJson) {
        return "{\"ContactListName\":\"" + name + "\",\"Topics\":" + topicsJson + "}";
    }

    private static String topics(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"TopicName\":\"t").append(i)
              .append("\",\"DisplayName\":\"D").append(i)
              .append("\",\"DefaultSubscriptionStatus\":\"OPT_IN\"}");
        }
        return sb.append("]").toString();
    }
}
