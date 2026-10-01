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
 * Paging of the v1 and v2 {@code ListCustomVerificationEmailTemplates} lists, which share one token
 * namespace. Runs in its own region so the templates created here are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesCustomVerificationEmailTemplatePagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-south-1/ses/aws4_request";
    private static final String BASE = "/v2/email/custom-verification-email-templates";
    private static final String FROM = "cvet-page-sender@floci.test";
    private static final String V1_ROOT =
            "ListCustomVerificationEmailTemplatesResponse.ListCustomVerificationEmailTemplatesResult.";

    @Test
    @Order(1)
    void createTemplatesOutOfNameOrder() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + FROM + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        for (String name : new String[] {"page-b", "page-c", "page-a"}) {
            create(name);
        }
    }

    @Test
    @Order(2)
    void v2_walksByNameAndEndsOnANullToken() {
        String token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get(BASE).then().statusCode(200)
                .body("CustomVerificationEmailTemplates.TemplateName", contains("page-a"))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        given().header("Authorization", AUTH).queryParam("PageSize", 2).queryParam("NextToken", token)
        .when().get(BASE).then().statusCode(200)
                .body("CustomVerificationEmailTemplates.TemplateName", contains("page-b", "page-c"))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void v1_walksByNameAndOmitsTheTokenOnTheLastPage() {
        XmlPath first = listV1("1", null);
        assertThat(first.getList(V1_ROOT + "CustomVerificationEmailTemplates.member.TemplateName"),
                contains("page-a"));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListCustomVerificationEmailTemplates").formParam("MaxResults", "2")
                .formParam("NextToken", first.getString(V1_ROOT + "NextToken"))
        .when().post("/").then().statusCode(200)
                .body(containsString("<TemplateName>page-c</TemplateName>"))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    @Order(4)
    void tokensCarryOverBetweenV1AndV2() {
        String v1Token = listV1("1", null).getString(V1_ROOT + "NextToken");
        given().header("Authorization", AUTH).queryParam("NextToken", v1Token)
        .when().get(BASE).then().statusCode(200)
                .body("CustomVerificationEmailTemplates.TemplateName", contains("page-b", "page-c"));

        String v2Token = given().header("Authorization", AUTH).queryParam("PageSize", 1)
        .when().get(BASE).then().statusCode(200)
                .extract().path("NextToken");
        assertThat(listV1(null, v2Token).getList(V1_ROOT + "CustomVerificationEmailTemplates.member.TemplateName"),
                contains("page-b", "page-c"));
    }

    @Test
    @Order(5)
    void outOfRangeSizesAndUnreadableTokensFollowEachApi() {
        given().header("Authorization", AUTH).queryParam("PageSize", 51)
        .when().get(BASE).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The page size must be between 1 and 50"));
        given().header("Authorization", AUTH).queryParam("NextToken", "garbage")
        .when().get(BASE).then().statusCode(400)
                .body("message", equalTo("Invalid nextToken <garbage>."));

        assertThat(listV1("0", null).getList(V1_ROOT + "CustomVerificationEmailTemplates.member.TemplateName"),
                contains("page-a", "page-b", "page-c"));
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListCustomVerificationEmailTemplates").formParam("NextToken", "garbage")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("<Message>Invalid nextToken &lt;garbage&gt;.</Message>"));
    }

    @Test
    @Order(6)
    void withoutAPageSizeOrOutOfRangeBothApisServeFifty() {
        for (int i = 0; i < 48; i++) {
            create("more-%02d".formatted(i));
        }

        given().header("Authorization", AUTH)
        .when().get(BASE).then().statusCode(200)
                .body("CustomVerificationEmailTemplates", hasSize(50))
                .body("NextToken", notNullValue());

        for (String maxResults : new String[] {null, "0", "51"}) {
            XmlPath v1 = listV1(maxResults, null);
            assertThat(v1.getList(V1_ROOT + "CustomVerificationEmailTemplates.member"), hasSize(50));
            assertThat(v1.getString(V1_ROOT + "NextToken"), not(emptyOrNullString()));
        }
    }

    private static void create(String name) {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "TemplateName": "%s",
                      "FromEmailAddress": "%s",
                      "TemplateSubject": "Verify",
                      "TemplateContent": "<html><body>verify</body></html>",
                      "SuccessRedirectionURL": "https://example.com/ok",
                      "FailureRedirectionURL": "https://example.com/fail"
                    }
                    """.formatted(name, FROM))
        .when().post(BASE).then().statusCode(200);
    }

    private static XmlPath listV1(String maxResults, String nextToken) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "ListCustomVerificationEmailTemplates");
        if (maxResults != null) {
            request.formParam("MaxResults", maxResults);
        }
        if (nextToken != null) {
            request.formParam("NextToken", nextToken);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }
}
