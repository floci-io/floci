package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SES v2 suppression list endpoints at /v2/email/suppression/addresses.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSuppressionV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void putSuppressedDestination_bounce() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "bounce-1@example.com", "Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void putSuppressedDestination_complaint() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "complaint-1@example.com", "Reason": "COMPLAINT"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void getSuppressedDestination_returnsStoredEntry() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses/bounce-1@example.com")
        .then()
            .statusCode(200)
            .body("SuppressedDestination.EmailAddress", equalTo("bounce-1@example.com"))
            .body("SuppressedDestination.Reason", equalTo("BOUNCE"))
            .body("SuppressedDestination.LastUpdateTime", notNullValue());
    }

    @Test
    @Order(4)
    void getSuppressedDestination_unknown_returns404() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses/missing@example.com")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(5)
    void putSuppressedDestination_updatesExistingReason() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "bounce-1@example.com", "Reason": "COMPLAINT"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses/bounce-1@example.com")
        .then()
            .statusCode(200)
            .body("SuppressedDestination.Reason", equalTo("COMPLAINT"));
    }

    @Test
    @Order(6)
    void listSuppressedDestinations_returnsAll() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses")
        .then()
            .statusCode(200)
            .body("SuppressedDestinationSummaries", hasSize(greaterThanOrEqualTo(2)))
            .body("SuppressedDestinationSummaries.EmailAddress",
                  hasItems("bounce-1@example.com", "complaint-1@example.com"));
    }

    @Test
    @Order(7)
    void listSuppressedDestinations_filteredByReason() {
        // Seed a BOUNCE entry so the filter has something to exclude — without it
        // every existing entry is already COMPLAINT (bounce-1 was flipped at @Order(5))
        // and the assertion would pass even if the server ignored the filter.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "filter-bounce@example.com", "Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("Reason", "COMPLAINT")
        .when()
            .get("/v2/email/suppression/addresses")
        .then()
            .statusCode(200)
            .body("SuppressedDestinationSummaries.Reason.unique()", contains("COMPLAINT"))
            .body("SuppressedDestinationSummaries.EmailAddress",
                  not(hasItem("filter-bounce@example.com")));

        // Clean up so the BOUNCE seed does not leak into later assertions
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/suppression/addresses/filter-bounce@example.com")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void deleteSuppressedDestination_removesEntry() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/suppression/addresses/bounce-1@example.com")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses/bounce-1@example.com")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void deleteSuppressedDestination_unknown_returns404() {
        // AWS DeleteSuppressedDestination is not idempotent — an unknown email
        // surfaces as NotFoundException with the same wording as GetSuppressedDestination.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/suppression/addresses/already-gone@example.com")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", containsString("does not exist on your suppression list"));
    }

    @Test
    @Order(10)
    void putSuppressedDestination_invalidReason_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "bad@example.com", "Reason": "OTHER"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(11)
    void putSuppressedDestination_missingEmailAddress_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(12)
    void putSuppressedDestination_missingBody_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(16)
    void putSuppressedDestination_nullEmailAddress_returns400() {
        // JSON null for EmailAddress must not coerce into the literal string "null".
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": null, "Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(17)
    void putSuppressedDestination_nullReason_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "null-reason@example.com", "Reason": null}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(18)
    void putSuppressedDestination_nonObjectBody_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("[\"not\", \"an\", \"object\"]")
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(13)
    void putSuppressedDestination_trimsWhitespaceFromEmailAddress() {
        // AWS silently trims leading/trailing whitespace from EmailAddress on Put.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "  padded@example.com  ", "Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/suppression/addresses/padded@example.com")
        .then()
            .statusCode(200)
            .body("SuppressedDestination.EmailAddress", equalTo("padded@example.com"));
    }

    @Test
    @Order(14)
    void listSuppressedDestinations_unknownReasonFilter_returns400() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("Reason", "OTHER")
        .when()
            .get("/v2/email/suppression/addresses")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(15)
    void listSuppressedDestinations_multipleReasonFilters_appliesOrSemantics() {
        // Seed a BOUNCE entry alongside the surviving complaint-1 COMPLAINT entry
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailAddress": "multi-bounce@example.com", "Reason": "BOUNCE"}
                """)
        .when()
            .put("/v2/email/suppression/addresses")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("Reason", "BOUNCE")
            .queryParam("Reason", "COMPLAINT")
        .when()
            .get("/v2/email/suppression/addresses")
        .then()
            .statusCode(200)
            .body("SuppressedDestinationSummaries.EmailAddress",
                  hasItems("multi-bounce@example.com", "complaint-1@example.com"));
    }
}
