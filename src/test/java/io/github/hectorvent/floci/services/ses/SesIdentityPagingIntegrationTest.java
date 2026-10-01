package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of the v1 {@code ListIdentities} and v2 {@code ListEmailIdentities} lists, which share one
 * token namespace. Runs in its own region so the identities created here are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityPagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-south-1/ses/aws4_request";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-north-1/ses/aws4_request";
    private static final String V1_ROOT = "ListIdentitiesResponse.ListIdentitiesResult.";

    @Test
    @Order(1)
    void createIdentitiesOutOfNameOrder() {
        for (String identity : new String[] {"page-b.test", "zz@page.test", "page-c.test", "page-a.test"}) {
            create(identity);
        }
    }

    @Test
    @Order(2)
    void v2_walksByNameAndEndsOnANullToken() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/identities").then().statusCode(200)
                .body("EmailIdentities.IdentityName", contains("page-a.test"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        given().header("Authorization", AUTH).queryParam("PageSize", 3).queryParam("NextToken", token)
        .when().get("/v2/email/identities").then().statusCode(200)
                .body("EmailIdentities.IdentityName", contains("page-b.test", "page-c.test", "zz@page.test"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void v1_walksByNameAndOmitsTheTokenOnTheLastPage() {
        XmlPath first = listV1("1", null, null);
        assertThat(first.getList(V1_ROOT + "Identities.member"), contains("page-a.test"));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListIdentities").formParam("MaxItems", "3")
                .formParam("NextToken", first.getString(V1_ROOT + "NextToken"))
        .when().post("/").then().statusCode(200)
                .body(containsString("<member>zz@page.test</member>"))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    @Order(4)
    void v1_aTokenTakenWithATypeFilterContinuesAListWithoutIt() {
        String token = listV1("1", null, "Domain").getString(V1_ROOT + "NextToken");

        assertThat(listV1(null, token, null).getList(V1_ROOT + "Identities.member"),
                contains("page-b.test", "page-c.test", "zz@page.test"));
    }

    @Test
    @Order(5)
    void tokensCarryOverBetweenV1AndV2() {
        String v1Token = listV1("1", null, null).getString(V1_ROOT + "NextToken");
        given().header("Authorization", AUTH).queryParam("NextToken", v1Token)
        .when().get("/v2/email/identities").then().statusCode(200)
                .body("EmailIdentities.IdentityName", contains("page-b.test", "page-c.test", "zz@page.test"));

        String v2Token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/identities").then().statusCode(200)
                .extract().path("NextToken");
        assertThat(listV1(null, v2Token, null).getList(V1_ROOT + "Identities.member"),
                contains("page-b.test", "page-c.test", "zz@page.test"));
    }

    @Test
    @Order(6)
    void outOfRangeSizesAndUnreadableTokensAreRefused() {
        given().header("Authorization", AUTH).queryParam("PageSize", 1001)
        .when().get("/v2/email/identities").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(
                        "Value 1001 for parameter PageSize is invalid. PageSize must be between 1 and 1000."));
        given().header("Authorization", AUTH).queryParam("NextToken", "garbage")
        .when().get("/v2/email/identities").then().statusCode(400)
                .body("message", equalTo("Invalid NextToken <garbage>."));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListIdentities").formParam("MaxItems", "0")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString(
                        "<Message>Value 0 for parameter MaxItems is invalid. MaxItems must be between 1 and 1000.</Message>"));
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListIdentities").formParam("NextToken", "garbage")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Message>Invalid NextToken &lt;garbage&gt;.</Message>"));
    }

    @Test
    @Order(7)
    void aTokenIsRefusedInAnotherRegion() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/identities").then().statusCode(200)
                .extract().path("NextToken");

        given().header("Authorization", OTHER_REGION_AUTH).queryParam("NextToken", token)
        .when().get("/v2/email/identities").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid NextToken <" + token + ">."));
        given().contentType("application/x-www-form-urlencoded").header("Authorization", OTHER_REGION_AUTH)
                .formParam("Action", "ListIdentities").formParam("NextToken", token)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>Invalid NextToken &lt;" + token + "&gt;.</Message>"));
    }

    @Test
    @Order(8)
    void withoutAPageSizeV2ServesTwentyFiveAndV1ServesEverything() {
        for (int i = 0; i < 22; i++) {
            create("more-%02d.test".formatted(i));
        }

        given().header("Authorization", AUTH)
        .when().get("/v2/email/identities").then().statusCode(200)
                .body("EmailIdentities", hasSize(25))
                .body("NextToken", notNullValue());

        XmlPath v1 = listV1(null, null, null);
        assertThat(v1.getList(V1_ROOT + "Identities.member"), hasSize(26));
        assertThat(v1.getString(V1_ROOT + "NextToken"), emptyOrNullString());
    }

    private static void create(String identity) {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + identity + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    private static XmlPath listV1(String maxItems, String nextToken, String identityType) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListIdentities");
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (nextToken != null) {
            request.formParam("NextToken", nextToken);
        }
        if (identityType != null) {
            request.formParam("IdentityType", identityType);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }
}
