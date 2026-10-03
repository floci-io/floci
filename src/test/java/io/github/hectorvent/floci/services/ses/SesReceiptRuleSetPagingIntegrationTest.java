package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Paging of v1 {@code ListReceiptRuleSets} on the Query wire: 100 rule sets a page and a token on
 * every page but the last. Runs in its own region so the rule sets created here are the whole list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleSetPagingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/il-central-1/email/aws4_request";
    private static final String ROOT = "ListReceiptRuleSetsResponse.ListReceiptRuleSetsResult.";

    @Test
    @Order(1)
    void createAHundredAndOneRuleSets() {
        for (int i = 0; i < 101; i++) {
            req("CreateReceiptRuleSet").formParam("RuleSetName", "page-%03d".formatted(i))
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(2)
    void servesAHundredThenTheRestOldestFirstAndOmitsTheTokenOnTheLastPage() {
        XmlPath first = req("ListReceiptRuleSets").when().post("/").then().statusCode(200)
                .extract().xmlPath();
        List<String> names = new ArrayList<>(first.getList(ROOT + "RuleSets.member.Name"));
        assertThat(names, hasSize(100));
        String token = first.getString(ROOT + "NextToken");
        assertThat(token, not(emptyOrNullString()));

        XmlPath rest = req("ListReceiptRuleSets").formParam("NextToken", token)
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<NextToken>")))
                .extract().xmlPath();
        assertThat(rest.getList(ROOT + "RuleSets.member.Name"), contains("page-100"));
        names.addAll(rest.getList(ROOT + "RuleSets.member.Name"));

        List<String> created = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            created.add("page-%03d".formatted(i));
        }
        assertEquals(created, names);
    }

    private static RequestSpecification req(String action) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action);
    }
}
