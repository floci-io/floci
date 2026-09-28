package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for idempotency token functionality with 1-hour TTL.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmIdempotencyTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void sameTokenReturnsExistingCertificate() {
        String token = "idempotency-test-" + UUID.randomUUID();
        String domain = "idempotent-" + UUID.randomUUID() + ".example.com";

        // First request
        String firstArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Second request with same token should return same ARN
        String secondArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", equalTo(firstArn))
            .extract().jsonPath().getString("CertificateArn");

        // Both should return the same certificate ARN
        assertEquals(firstArn, secondArn);
    }

    @Test
    @Order(2)
    void differentTokenCreatesNewCertificate() {
        String domain = "multi-token-" + UUID.randomUUID() + ".example.com";

        // First request
        String firstArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "IdempotencyToken": "token-1-%s"
                }
                """.formatted(domain, UUID.randomUUID()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Second request with different token should create new certificate
        String secondArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "IdempotencyToken": "token-2-%s"
                }
                """.formatted(domain, UUID.randomUUID()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // ARNs should be different
        assertNotEquals(firstArn, secondArn);
    }

    @Test
    @Order(3)
    void sameTokenDifferentParamsThrowsIdempotencyException() {
        String token = "param-mismatch-" + UUID.randomUUID();

        // First request with domain A
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "domain-a-%s.example.com",
                    "IdempotencyToken": "%s"
                }
                """.formatted(UUID.randomUUID(), token))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Second request with same token but different domain - should fail
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "domain-b-%s.example.com",
                    "IdempotencyToken": "%s"
                }
                """.formatted(UUID.randomUUID(), token))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IdempotencyException"));
    }

    @Test
    @Order(4)
    void sameTokenDifferentKeyAlgorithmThrowsIdempotencyException() {
        String token = "key-algo-mismatch-" + UUID.randomUUID();
        String domain = "key-algo-" + UUID.randomUUID() + ".example.com";

        // First request with RSA-2048
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "KeyAlgorithm": "RSA_2048",
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Second request with same token but different key algorithm - should fail
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "KeyAlgorithm": "EC_prime256v1",
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IdempotencyException"));
    }

    @Test
    @Order(5)
    void noTokenAlwaysCreatesNewCertificate() {
        String domain = "no-token-" + UUID.randomUUID() + ".example.com";

        // First request without token
        String firstArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s"
                }
                """.formatted(domain))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Second request without token should create new certificate
        String secondArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s"
                }
                """.formatted(domain))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        assertNotEquals(firstArn, secondArn);
    }

    @Test
    @Order(6)
    void sameTokenSameSansReturnsExistingCertificate() {
        String token = "sans-match-" + UUID.randomUUID();
        String domain = "sans-" + UUID.randomUUID() + ".example.com";

        // First request with SANs
        String firstArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "SubjectAlternativeNames": ["www.%s", "api.%s"],
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, domain, domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Same request should return same ARN
        String secondArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s",
                    "SubjectAlternativeNames": ["www.%s", "api.%s"],
                    "IdempotencyToken": "%s"
                }
                """.formatted(domain, domain, domain, token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        assertEquals(firstArn, secondArn);
    }
}
