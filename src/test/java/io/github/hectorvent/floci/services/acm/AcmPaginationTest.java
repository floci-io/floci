package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListCertificates pagination functionality.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmPaginationTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final int TOTAL_CERTS = 15;
    private final List<String> createdArns = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createMultipleCertificates() {
        // Create 15 certificates for pagination testing
        for (int i = 0; i < TOTAL_CERTS; i++) {
            String arn = given()
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType(ACM_CONTENT_TYPE)
                .body("""
                    {
                        "DomainName": "pagination-test-%d.example.com"
                    }
                    """.formatted(i))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().jsonPath().getString("CertificateArn");

            createdArns.add(arn);
        }

        assertEquals(TOTAL_CERTS, createdArns.size());
    }

    @Test
    @Order(2)
    void listWithMaxItems() {
        // List with maxItems=5, expect nextToken
        Response response = given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "MaxItems": 5
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateSummaryList.size()", equalTo(5))
            .body("NextToken", notNullValue())
            .extract().response();

        String nextToken = response.jsonPath().getString("NextToken");
        assertNotNull(nextToken, "NextToken should be present when there are more pages");
    }

    @Test
    @Order(3)
    void paginateThroughAllCertificates() {
        Set<String> allArns = new HashSet<>();
        String nextToken = null;
        int pageCount = 0;
        int maxPages = 10; // Safety limit

        do {
            String body = nextToken == null
                ? "{\"MaxItems\": 5}"
                : "{\"MaxItems\": 5, \"NextToken\": \"" + nextToken + "\"}";

            Response response = given()
                .header("X-Amz-Target", "CertificateManager.ListCertificates")
                .contentType(ACM_CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().response();

            List<String> pageArns = response.jsonPath().getList("CertificateSummaryList.CertificateArn");
            allArns.addAll(pageArns);
            nextToken = response.jsonPath().getString("NextToken");
            pageCount++;

        } while (nextToken != null && pageCount < maxPages);

        // Should have retrieved all created certificates (plus possibly others from other tests)
        assertTrue(allArns.containsAll(createdArns), "All created certificates should be retrievable via pagination");
    }

    @Test
    @Order(4)
    void invalidNextToken() {
        given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "NextToken": "invalid-token-not-base64"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidNextTokenException"));
    }

    @Test
    @Order(5)
    void emptyListReturnsNoNextToken() {
        // Filter by a status none of this class's own certificates ever have. Can't assert the
        // whole result is empty -- other test classes in the shared full-suite JVM (e.g.
        // AcmIntegrationTest) revoke certificates of their own, and that state persists across
        // classes. What this test actually verifies -- filtering excludes non-matching
        // certificates and a small result page carries no NextToken -- holds regardless.
        Response response = given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateStatuses": ["REVOKED"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().response();

        List<String> revokedArns = response.jsonPath().getList("CertificateSummaryList.CertificateArn");
        assertTrue(createdArns.stream().noneMatch(revokedArns::contains),
                "none of this class's own (always-ISSUED) certificates should appear under a REVOKED filter");
        assertNull(response.jsonPath().get("NextToken"));
    }

    @Test
    @Order(6)
    void maxItemsLimit() {
        // MaxItems should be capped at 1000
        given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "MaxItems": 2000
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateSummaryList.size()", lessThanOrEqualTo(1000));
    }
}
