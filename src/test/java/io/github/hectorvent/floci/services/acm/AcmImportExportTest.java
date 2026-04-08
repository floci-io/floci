package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmImportExportTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    CertificateGenerator certificateGenerator;

    // Generated test certificate data
    private String validTestCertificate;
    private String validTestPrivateKey;

    private String importedCertificateArn;
    private String exportableCertificateArn;

    @BeforeAll
    void setupTestCertificates() {
        RestAssuredJsonUtils.configureAwsContentTypes();

        // Generate a valid test certificate using CertificateGenerator
        CertificateGenerator.GeneratedCertificate generated = certificateGenerator.generateCertificate(
            "test-import.example.com",
            List.of("www.test-import.example.com"),
            KeyAlgorithm.RSA_2048
        );

        validTestCertificate = generated.certificatePem();
        validTestPrivateKey = generated.privateKeyPem();
    }

    // ==================== ImportCertificate Tests ====================

    @Test
    @Order(1)
    void importCertificateBasic() {
        // Escape newlines for JSON
        String certJson = validTestCertificate.replace("\n", "\\n");
        String keyJson = validTestPrivateKey.replace("\n", "\\n");

        importedCertificateArn = given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Certificate": "%s",
                    "PrivateKey": "%s"
                }
                """.formatted(certJson, keyJson))
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
    void verifyImportedCertificate() {
        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(importedCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.CertificateArn", equalTo(importedCertificateArn))
            .body("Certificate.DomainName", equalTo("test-import.example.com"))
            .body("Certificate.Status", equalTo("ISSUED"))
            .body("Certificate.Type", equalTo("IMPORTED"))
            .body("Certificate.KeyAlgorithm", equalTo("RSA-2048"));
    }

    @Test
    @Order(3)
    void importCertificateWithTags() {
        String certJson = validTestCertificate.replace("\n", "\\n");
        String keyJson = validTestPrivateKey.replace("\n", "\\n");

        String taggedArn = given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Certificate": "%s",
                    "PrivateKey": "%s",
                    "Tags": [
                        {"Key": "Environment", "Value": "test"},
                        {"Key": "ImportTest", "Value": "true"}
                    ]
                }
                """.formatted(certJson, keyJson))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");

        // Verify tags were applied
        given()
            .header("X-Amz-Target", "CertificateManager.ListTagsForCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(taggedArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(2));
    }

    @Test
    @Order(4)
    void importCertificateReimport() {
        String certJson = validTestCertificate.replace("\n", "\\n");
        String keyJson = validTestPrivateKey.replace("\n", "\\n");

        // Re-import to the same ARN should succeed
        given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Certificate": "%s",
                    "PrivateKey": "%s",
                    "CertificateArn": "%s"
                }
                """.formatted(certJson, keyJson, importedCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", equalTo(importedCertificateArn));
    }

    // ==================== ExportCertificate Tests ====================

    @Test
    @Order(10)
    void requestExportableCertificate() {
        // Create a PRIVATE type certificate that can be exported
        exportableCertificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "exportable.example.com",
                    "CertificateAuthorityArn": "arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/12345678-1234-1234-1234-123456789012"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");
    }

    @Test
    @Order(11)
    void exportCertificate() {
        String passphrase = Base64.getEncoder().encodeToString("testpassphrase".getBytes());

        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Passphrase": "%s"
                }
                """.formatted(exportableCertificateArn, passphrase))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("PrivateKey", startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
    }

    @Test
    @Order(12)
    void exportCertificateShortPassphraseFails() {
        String shortPassphrase = Base64.getEncoder().encodeToString("abc".getBytes());

        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Passphrase": "%s"
                }
                """.formatted(exportableCertificateArn, shortPassphrase))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(13)
    void exportNonExportableCertificateFails() {
        // Create a non-exportable certificate (AMAZON_ISSUED without Export option)
        String nonExportableArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "non-exportable.example.com"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        String passphrase = Base64.getEncoder().encodeToString("testpassphrase".getBytes());

        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Passphrase": "%s"
                }
                """.formatted(nonExportableArn, passphrase))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(14)
    void exportImportedCertificate() {
        // Imported certificates should be exportable (they have a private key)
        String certJson = validTestCertificate.replace("\n", "\\n");
        String keyJson = validTestPrivateKey.replace("\n", "\\n");

        String arn = given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Certificate": "%s",
                    "PrivateKey": "%s"
                }
                """.formatted(certJson, keyJson))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        String passphrase = Base64.getEncoder().encodeToString("testpassphrase".getBytes());

        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Passphrase": "%s"
                }
                """.formatted(arn, passphrase))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("PrivateKey", startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
    }

    @Test
    @Order(16)
    void exportCertificateNotFoundFails() {
        String passphrase = Base64.getEncoder().encodeToString("testpassphrase".getBytes());

        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "arn:aws:acm:us-east-1:123456789012:certificate/nonexistent",
                    "Passphrase": "%s"
                }
                """.formatted(passphrase))
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(17)
    void importInvalidCertificateFails() {
        given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "Certificate": "not-a-valid-certificate",
                    "PrivateKey": "not-a-valid-key"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
