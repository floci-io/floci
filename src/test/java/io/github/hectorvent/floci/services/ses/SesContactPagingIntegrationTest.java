package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of {@code ListContactLists} and {@code ListContacts}. Runs in its own region so the one
 * contact list a region may hold is the list created here.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesContactPagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-northeast-2/ses/aws4_request";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-north-1/ses/aws4_request";
    private static final String LIST = "page-list";
    private static final String CONTACTS = "/v2/email/contact-lists/" + LIST + "/contacts";

    @Test
    @Order(1)
    void createAListAndContactsOutOfAddressOrder() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ContactListName\":\"" + LIST + "\"}")
        .when().post("/v2/email/contact-lists").then().statusCode(200);
        for (String name : new String[] {"page-b", "page-c", "page-a"}) {
            create(name);
        }
    }

    @Test
    @Order(2)
    void contacts_walkByAddressAndEndOnANullToken() {
        String token = list("{\"PageSize\":1}").statusCode(200)
                .body("Contacts.EmailAddress", contains("page-a@example.com"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        list("{\"PageSize\":2,\"NextToken\":\"" + token + "\"}").statusCode(200)
                .body("Contacts.EmailAddress", contains("page-b@example.com", "page-c@example.com"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void contacts_outOfRangeSizesAndUnreadableTokensAreRefused() {
        for (int size : new int[] {0, 1001}) {
            list("{\"PageSize\":" + size + "}").statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("The page size must be between 1 and 1000"));
        }
        for (String token : new String[] {"garbage", ""}) {
            list("{\"NextToken\":\"" + token + "\"}").statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("Provided NextToken is invalid"));
        }
        list("{\"PageSize\":0,\"NextToken\":\"garbage\"}").statusCode(400)
                .body("message", equalTo("The page size must be between 1 and 1000"));
    }

    @Test
    @Order(4)
    void contacts_aTokenIsReadBeforeTheListIsLookedUp() {
        String token = list("{\"PageSize\":1}").statusCode(200).extract().path("NextToken");

        given().contentType("application/json").header("Authorization", OTHER_REGION_AUTH)
                .body("{\"NextToken\":\"" + token + "\"}")
        .when().post(CONTACTS + "/list").then().statusCode(400)
                .body("message", equalTo("Provided NextToken is invalid"));
        given().contentType("application/json").header("Authorization", OTHER_REGION_AUTH).body("{}")
        .when().post(CONTACTS + "/list").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(5)
    void contactLists_pageAndRefuseAContactsToken() {
        given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/contact-lists").then().statusCode(200)
                .body("ContactLists.ContactListName", contains(LIST))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());

        given().header("Authorization", AUTH).queryParam("PageSize", 1001)
        .when().get("/v2/email/contact-lists").then().statusCode(400)
                .body("message", equalTo("The page size must be between 1 and 1000"));
        String contactsToken = list("{\"PageSize\":1}").statusCode(200).extract().path("NextToken");
        for (String token : new String[] {"garbage", "", contactsToken}) {
            given().header("Authorization", AUTH).queryParam("NextToken", token)
            .when().get("/v2/email/contact-lists").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("Provided NextToken is invalid"));
        }
    }

    @Test
    @Order(6)
    void contacts_withoutAPageSizeServeFifty() {
        for (int i = 0; i < 48; i++) {
            create("more-%02d".formatted(i));
        }

        String token = list("{}").statusCode(200)
                .body("Contacts", hasSize(50))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");
        list("{\"NextToken\":\"" + token + "\"}").statusCode(200)
                .body("Contacts.EmailAddress", contains("page-c@example.com"))
                .body("NextToken", nullValue());
    }

    private static void create(String name) {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailAddress\":\"" + name + "@example.com\"}")
        .when().post(CONTACTS).then().statusCode(200);
    }

    private static ValidatableResponse list(String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
        .when().post(CONTACTS + "/list").then();
    }
}
