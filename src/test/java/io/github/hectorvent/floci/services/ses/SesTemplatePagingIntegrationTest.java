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
 * Paging of the v1 {@code ListTemplates} and v2 {@code ListEmailTemplates} lists, which share one
 * token namespace. Runs in its own region so the three templates are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTemplatePagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-southeast-2/ses/aws4_request";
    // A second region holding eleven templates, one more than the default page.
    private static final String AUTH_ELEVEN =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/ap-southeast-3/ses/aws4_request";
    private static final String V1_ROOT = "ListTemplatesResponse.ListTemplatesResult.";

    @Test
    @Order(1)
    void createTemplatesOutOfNameOrder() throws InterruptedException {
        for (String name : new String[] {"page-b", "page-c", "page-a"}) {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("""
                        {"TemplateName": "%s", "TemplateContent": {"Subject": "s", "Text": "t"}}
                        """.formatted(name))
            .when().post("/v2/email/templates").then().statusCode(200);
            // Distinct creation times, so the order below is by time and not by the name tiebreak.
            Thread.sleep(5);
        }
    }

    @Test
    @Order(2)
    void v2_walksNewestFirstAndEndsOnANullToken() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-a"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        token = given().header("Authorization", AUTH)
                .queryParam("PageSize", 1).queryParam("NextToken", token)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-c"))
                .extract().path("NextToken");

        given().header("Authorization", AUTH)
                .queryParam("PageSize", 1).queryParam("NextToken", token)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-b"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void v2_withoutPagingReturnsTheWholeListAndANullToken() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-a", "page-c", "page-b"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());

        given().header("Authorization", AUTH).queryParam("NextToken", "").queryParam("PageSize", "")
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-a", "page-c", "page-b"));
    }

    @Test
    @Order(4)
    void v2_refusesAPageSizeOutsideOneToAHundred() {
        for (int size : new int[] {0, 101}) {
            given().header("Authorization", AUTH).queryParam("PageSize", size)
            .when().get("/v2/email/templates").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("The page size must be between 1 and 100"));
        }
        given().header("Authorization", AUTH).queryParam("PageSize", 100)
        .when().get("/v2/email/templates").then().statusCode(200);
    }

    @Test
    @Order(5)
    void v2_refusesANonIntegerPageSizeAndAnUnreadableToken() {
        given().header("Authorization", AUTH).queryParam("PageSize", "abc")
        .when().get("/v2/email/templates").then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("'abc' can not be converted to Integer"));

        given().header("Authorization", AUTH).queryParam("NextToken", "garbage")
        .when().get("/v2/email/templates").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid PageToken <garbage>."));
    }

    @Test
    @Order(6)
    void v1_walksNewestFirstAndOmitsTheTokenOnTheLastPage() {
        XmlPath first = listV1("1", null);
        assertThat(first.getList(V1_ROOT + "TemplatesMetadata.member.Name"), contains("page-a"));
        String token = first.getString(V1_ROOT + "NextToken");

        XmlPath second = listV1("1", token);
        assertThat(second.getList(V1_ROOT + "TemplatesMetadata.member.Name"), contains("page-c"));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListTemplates").formParam("MaxItems", "1")
                .formParam("NextToken", second.getString(V1_ROOT + "NextToken"))
        .when().post("/").then().statusCode(200)
                .body(containsString("<Name>page-b</Name>"))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    @Order(7)
    void v1_servesAnOutOfRangeMaxItemsInsteadOfRefusingIt() {
        for (String size : new String[] {"0", "-1", "1001"}) {
            XmlPath page = listV1(size, null);
            assertThat(page.getList(V1_ROOT + "TemplatesMetadata.member.Name"),
                    contains("page-a", "page-c", "page-b"));
        }
    }

    @Test
    @Order(8)
    void v1_refusesANonIntegerMaxItemsAndAnUnreadableToken() {
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListTemplates").formParam("MaxItems", "abc")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"))
                .body(not(containsString("<Message>")));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListTemplates").formParam("MaxItems", "")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"))
                .body(containsString("<Message>missing value for decimal type</Message>"));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListTemplates").formParam("NextToken", "garbage")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>Invalid PageToken &lt;garbage&gt;.</Message>"));
    }

    @Test
    @Order(9)
    void tokensCarryOverBetweenV1AndV2() {
        String v1Token = listV1("1", null).getString(V1_ROOT + "NextToken");
        given().header("Authorization", AUTH).queryParam("NextToken", v1Token)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata.TemplateName", contains("page-c", "page-b"));

        String v2Token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get("/v2/email/templates").then().statusCode(200)
                .extract().path("NextToken");
        assertThat(listV1(null, v2Token).getList(V1_ROOT + "TemplatesMetadata.member.Name"),
                contains("page-c", "page-b"));
    }

    @Test
    @Order(10)
    void withoutAPageSizeBothListsServeTen() {
        for (int i = 0; i < 11; i++) {
            given().contentType("application/json").header("Authorization", AUTH_ELEVEN)
                    .body("""
                        {"TemplateName": "eleven-%02d", "TemplateContent": {"Subject": "s", "Text": "t"}}
                        """.formatted(i))
            .when().post("/v2/email/templates").then().statusCode(200);
        }

        given().header("Authorization", AUTH_ELEVEN)
        .when().get("/v2/email/templates").then().statusCode(200)
                .body("TemplatesMetadata", hasSize(10))
                .body("NextToken", notNullValue());

        XmlPath v1Default = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_ELEVEN).formParam("Action", "ListTemplates")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        assertThat(v1Default.getList(V1_ROOT + "TemplatesMetadata.member.Name"), hasSize(10));
        assertThat(v1Default.getString(V1_ROOT + "NextToken"), not(emptyOrNullString()));

        // An out-of-range MaxItems is served the bound, not the default.
        XmlPath outOfRange = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_ELEVEN)
                .formParam("Action", "ListTemplates").formParam("MaxItems", "0")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        assertThat(outOfRange.getList(V1_ROOT + "TemplatesMetadata.member.Name"), hasSize(11));
    }

    private static XmlPath listV1(String maxItems, String nextToken) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListTemplates");
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (nextToken != null) {
            request.formParam("NextToken", nextToken);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }
}
