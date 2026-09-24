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
 * <p>Covers the two cases with no container behind the domain: the service is mocked, and no Docker
 * daemon is reachable. Both once reported {@code Processing false} alongside {@code Endpoint ""},
 * which AWS never returns, leaving a client that reads the endpoint with an empty string to address.
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

    /** The DNS suffix comes from the region's partition, so a China region must not report .com. */
    @Test
    void theEndpointTakesItsDnsSuffixFromThePartition() {
        String domain = "endpoint-cn-probe";
        try {
            given()
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/cn-north-1/es/aws4_request")
                .contentType("application/json")
                .body("{\"DomainName\":\"" + domain + "\",\"EngineVersion\":\"OpenSearch_2.11\"}")
            .when()
                .post("/2021-01-01/opensearch/domain")
            .then()
                .statusCode(200)
                .body("DomainStatus.Endpoint", endsWith(".cn-north-1.es.amazonaws.com.cn"));
        } finally {
            given().header("Authorization",
                            "AWS4-HMAC-SHA256 Credential=AKID/20260101/cn-north-1/es/aws4_request")
                .when().delete("/2021-01-01/opensearch/domain/" + domain);
        }
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
