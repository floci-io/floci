package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * A domain that reports itself no longer processing also reports an endpoint.
 *
 * <p>An SDK waiter polls DescribeDomain until the domain is active and names an endpoint. A blank
 * one with {@code Processing false} satisfies neither exit, so a create hangs until the caller's
 * own deadline rather than failing.
 */
@QuarkusTest
class OpenSearchDomainEndpointIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";
    private static final String DOMAIN = "endpoint-probe";

    @AfterEach
    void cleanup() {
        given().header("Authorization", AUTH_HEADER)
            .when().delete("/2021-01-01/opensearch/domain/" + DOMAIN);
    }

    @Test
    void createAndDescribeReportAnEndpointBesideProcessingFalse() {
        given()
            .header("Authorization", AUTH_HEADER)
            .contentType("application/json")
            .body("{\"DomainName\":\"" + DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
        .when()
            .post("/2021-01-01/opensearch/domain")
        .then()
            .statusCode(200)
            .body("DomainStatus.Processing", is(false))
            .body("DomainStatus.Endpoint", not(emptyString()))
            .body("DomainStatus.Endpoint", startsWith("search-" + DOMAIN + "-"))
            .body("DomainStatus.Endpoint", endsWith(".us-east-1.es.amazonaws.com"));

        String first = given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN)
        .then()
            .statusCode(200)
            .body("DomainStatus.Processing", is(false))
            .body("DomainStatus.Endpoint", not(emptyString()))
            .extract().path("DomainStatus.Endpoint");

        // The waiter polls, so the value has to be the same every time.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/2021-01-01/opensearch/domain/" + DOMAIN)
        .then()
            .statusCode(200)
            .body("DomainStatus.Endpoint", is(first));
    }
}
