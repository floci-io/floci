package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the SES V1 Query-protocol receipt-filter actions. Filters are stored
 * inertly (Floci accepts no inbound connections to filter), but the wire behavior follows real
 * AWS as probed: single-layer filterName validation, Smithy violations for missing IpFilter
 * members, the "Invalid CIDR block" service check, AlreadyExists on duplicates, and an
 * idempotent delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptFilterV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";

    private static io.restassured.specification.RequestSpecification req(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    private static io.restassured.specification.RequestSpecification create(
            String name, String policy, String cidr) {
        return req("CreateReceiptFilter")
                .formParam("Filter.Name", name)
                .formParam("Filter.IpFilter.Policy", policy)
                .formParam("Filter.IpFilter.Cidr", cidr);
    }

    @Test
    @Order(1)
    void createAndList_roundTrip() {
        create("floci-filter-block", "Block", "10.0.0.0/24")
        .when().post("/").then().statusCode(200)
                .body(containsString("CreateReceiptFilterResponse"));
        create("floci-filter-allow", "Allow", "192.0.2.10")
        .when().post("/").then().statusCode(200);

        String body = req("ListReceiptFilters")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Name>floci-filter-block</Name>"))
                .body(containsString("<Policy>Block</Policy>"))
                .body(containsString("<Cidr>10.0.0.0/24</Cidr>"))
                .body(containsString("<Cidr>192.0.2.10</Cidr>"))
                .extract().asString();
        assertTrue(body.indexOf("floci-filter-allow") < body.indexOf("floci-filter-block"));
    }

    @Test
    @Order(2)
    void duplicateCreate_returnsAlreadyExists() {
        create("floci-filter-block", "Allow", "10.9.9.9")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>AlreadyExists</Code>"))
                .body(containsString("Filter already exists: floci-filter-block"));
    }

    @Test
    @Order(3)
    void bareCreate_returnsSingleFilterViolation() {
        // Probed: with no Filter member at all, the whole structure is the one violation.
        req("CreateReceiptFilter")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("1 validation error detected"))
                .body(containsString("&apos;filter&apos;"));
    }

    @Test
    @Order(3)
    void invalidInputs_matchProbedErrors() {
        create("bad name", "Block", "10.0.0.0/24")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Not a valid filterName: bad name"));

        create("floci-filter-x", "Bogus", "10.0.0.0/24")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("&apos;filter.ipFilter.policy&apos;"))
                .body(containsString("[Allow, Block]"));

        create("floci-filter-x", "Block", "not-a-cidr")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Invalid CIDR block: not-a-cidr"));

        req("CreateReceiptFilter").formParam("Filter.Name", "floci-filter-x")
        .when().post("/").then().statusCode(400)
                .body(containsString("&apos;filter.ipFilter&apos;"))
                .body(containsString("Member must not be null"));
    }

    @Test
    @Order(4)
    void deleteFilter_isIdempotent_andRequiresName() {
        req("DeleteReceiptFilter").formParam("FilterName", "floci-filter-allow")
        .when().post("/").then().statusCode(200);
        req("DeleteReceiptFilter").formParam("FilterName", "floci-filter-allow")
        .when().post("/").then().statusCode(200);

        req("ListReceiptFilters")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("floci-filter-allow")));

        req("DeleteReceiptFilter")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("&apos;filterName&apos;"));

        req("DeleteReceiptFilter").formParam("FilterName", "floci-filter-block")
        .when().post("/").then().statusCode(200);
    }
}
