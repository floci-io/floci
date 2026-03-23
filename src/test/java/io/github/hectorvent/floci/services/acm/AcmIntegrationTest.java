package io.github.hectorvent.floci.services.acm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmIntegrationTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static String createdCertificateArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(ACM_CONTENT_TYPE, ContentType.TEXT));
    }

    // ==================== User Story 1: RequestCertificate ====================

    @Test
    @Order(1)
    void requestCertificateBasic() {
        createdCertificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "example.com"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .body("CertificateArn", containsString(":certificate/"))
            .extract().jsonPath().getString("CertificateArn");
    }

    @Test
    @Order(2)
    void requestCertificateWithSans() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "api.example.com",
                    "SubjectAlternativeNames": ["www.example.com", "*.example.com"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    @Order(3)
    void requestCertificateWithDnsValidation() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "dns-validated.example.com",
                    "ValidationMethod": "DNS"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    @Order(4)
    void requestCertificateWithEmailValidation() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "email-validated.example.com",
                    "ValidationMethod": "EMAIL"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    @Order(5)
    void requestCertificateWithKeyAlgorithm() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "ec-cert.example.com",
                    "KeyAlgorithm": "EC_prime256v1"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    @Order(6)
    void requestCertificateWithIdempotencyToken() {
        String token = "test-idempotency-token-123";

        // First request
        String arn1 = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "idempotent.example.com",
                    "IdempotencyToken": "%s"
                }
                """.formatted(token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Second request with same token should return same ARN
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "idempotent.example.com",
                    "IdempotencyToken": "%s"
                }
                """.formatted(token))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", equalTo(arn1));
    }

    @Test
    @Order(7)
    void requestCertificateWithTags() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "tagged.example.com",
                    "Tags": [
                        {"Key": "Environment", "Value": "test"},
                        {"Key": "Project", "Value": "demo"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    @Order(8)
    void requestCertificateEmptyDomainFails() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": ""
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== User Story 2: DescribeCertificate ====================

    @Test
    @Order(10)
    void describeCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.CertificateArn", equalTo(createdCertificateArn))
            .body("Certificate.DomainName", equalTo("example.com"))
            .body("Certificate.Status", equalTo("ISSUED"))
            .body("Certificate.Type", equalTo("AMAZON_ISSUED"))
            .body("Certificate.Serial", notNullValue())
            .body("Certificate.Subject", startsWith("CN="))
            .body("Certificate.Issuer", notNullValue())
            .body("Certificate.KeyAlgorithm", equalTo("RSA-2048"))
            .body("Certificate.NotBefore", notNullValue())
            .body("Certificate.NotAfter", notNullValue());
    }

    @Test
    @Order(11)
    void describeCertificateNotFound() {
        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "arn:aws:acm:us-east-1:123456789012:certificate/nonexistent"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ==================== User Story 2: GetCertificate ====================

    @Test
    @Order(12)
    void getCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.GetCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("CertificateChain", startsWith("-----BEGIN CERTIFICATE-----"));
    }

    // ==================== User Story 2: ListCertificates ====================

    @Test
    @Order(13)
    void listCertificates() {
        given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateSummaryList", notNullValue())
            .body("CertificateSummaryList.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(14)
    void listCertificatesWithStatusFilter() {
        given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateStatuses": ["ISSUED"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateSummaryList", notNullValue());
    }

    @Test
    @Order(15)
    void listCertificatesWithKeyTypeFilter() {
        given()
            .header("X-Amz-Target", "CertificateManager.ListCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Includes": {
                        "keyTypes": ["RSA_2048"]
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateSummaryList", notNullValue());
    }

    // ==================== User Story 5: Tagging ====================

    @Test
    @Order(20)
    void addTagsToCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.AddTagsToCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Tags": [
                        {"Key": "Team", "Value": "Engineering"},
                        {"Key": "Cost-Center", "Value": "12345"}
                    ]
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void listTagsForCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.ListTagsForCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", notNullValue())
            .body("Tags.size()", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(22)
    void removeTagsFromCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.RemoveTagsFromCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Tags": [
                        {"Key": "Cost-Center"}
                    ]
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify tag was removed
        given()
            .header("X-Amz-Target", "CertificateManager.ListTagsForCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'Cost-Center' }", nullValue());
    }

    @Test
    @Order(23)
    void addTagsInvalidKeyFails() {
        given()
            .header("X-Amz-Target", "CertificateManager.AddTagsToCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Tags": [
                        {"Key": "aws:reserved", "Value": "not-allowed"}
                    ]
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== Account Configuration ====================

    @Test
    @Order(30)
    void getAccountConfiguration() {
        given()
            .header("X-Amz-Target", "CertificateManager.GetAccountConfiguration")
            .contentType(ACM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ExpiryEvents.DaysBeforeExpiry", equalTo(45));
    }

    @Test
    @Order(31)
    void putAccountConfiguration() {
        given()
            .header("X-Amz-Target", "CertificateManager.PutAccountConfiguration")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "ExpiryEvents": {
                        "DaysBeforeExpiry": 30
                    },
                    "IdempotencyToken": "config-token-123"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify configuration was updated
        given()
            .header("X-Amz-Target", "CertificateManager.GetAccountConfiguration")
            .contentType(ACM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ExpiryEvents.DaysBeforeExpiry", equalTo(30));
    }

    // ==================== User Story 3: DeleteCertificate ====================

    @Test
    @Order(100)
    void deleteCertificate() {
        // Create a certificate to delete
        String arnToDelete = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "to-delete.example.com"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Delete it
        given()
            .header("X-Amz-Target", "CertificateManager.DeleteCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(arnToDelete))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(arnToDelete))
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(101)
    void deleteCertificateNotFound() {
        given()
            .header("X-Amz-Target", "CertificateManager.DeleteCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "arn:aws:acm:us-east-1:123456789012:certificate/nonexistent"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ==================== Unsupported Operation ====================

    @Test
    void unsupportedOperation() {
        given()
            .header("X-Amz-Target", "CertificateManager.UnsupportedAction")
            .contentType(ACM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedOperation"));
    }
}
