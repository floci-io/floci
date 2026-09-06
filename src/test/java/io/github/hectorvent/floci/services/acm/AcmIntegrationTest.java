package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmIntegrationTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static String createdCertificateArn;
    private static String ecCertificateArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
        ecCertificateArn = given()
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
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");
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
            .body("Certificate.Issuer", equalTo("CN=Floci Local CA"))
            .body("Certificate.KeyAlgorithm", equalTo("RSA-2048"))
            .body("Certificate.NotBefore", notNullValue())
            .body("Certificate.NotAfter", notNullValue())
            .body("Certificate.DomainValidationOptions.ValidationStatus", everyItem(equalTo("SUCCESS")))
            .body("Certificate.DomainValidationOptions.ResourceRecord.Type", everyItem(equalTo("CNAME")));
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
    void getCertificate() throws Exception {
        var response = getCertificatePems(createdCertificateArn);

        X509Certificate leaf = assertLeafChainsToLocalCa(response.getString("Certificate"),
                response.getString("CertificateChain"));
        assertEquals("RSA", leaf.getPublicKey().getAlgorithm());
        assertEquals(2048, ((RSAPublicKey) leaf.getPublicKey()).getModulus().bitLength(),
                "RSA_2048 is the default KeyAlgorithm");
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

    @Test
    @Order(16)
    void privateCertificateChainsToTheSameCa() throws Exception {
        String arn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "internal.example.com",
                    "CertificateAuthorityArn": "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/11111111-2222-3333-4444-555555555555"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        var response = getCertificatePems(arn);

        assertLeafChainsToLocalCa(response.getString("Certificate"), response.getString("CertificateChain"));
    }

    @Test
    @Order(17)
    void ecCertificateKeepsItsKeyAlgorithmAndChainsToTheSameCa() throws Exception {
        var response = getCertificatePems(ecCertificateArn);

        X509Certificate leaf = assertLeafChainsToLocalCa(response.getString("Certificate"),
                response.getString("CertificateChain"));
        assertEquals("EC", leaf.getPublicKey().getAlgorithm(), "the requested KeyAlgorithm is honoured");
        assertEquals(256, ((ECPublicKey) leaf.getPublicKey()).getParams().getCurve().getField().getFieldSize(),
                "EC_prime256v1 is a P-256 key");
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

    @Test
    @Order(40)
    void updateCertificateOptions() {
        given()
            .header("X-Amz-Target", "CertificateManager.UpdateCertificateOptions")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Options": {"Export": "ENABLED"}
                }
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s"}
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.Options.Export", equalTo("ENABLED"));
    }

    @Test
    @Order(41)
    void updateCertificateOptionsRequiresOptions() {
        given()
            .header("X-Amz-Target", "CertificateManager.UpdateCertificateOptions")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s"}
                """.formatted(createdCertificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(42)
    void revokeCertificate() {
        String certificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"DomainName": "revocable.example.com"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        given()
            .header("X-Amz-Target", "CertificateManager.UpdateCertificateOptions")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s", "Options": {"Export": "ENABLED"}}
                """.formatted(certificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CertificateManager.RevokeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s", "RevocationReason": "KEY_COMPROMISE"}
                """.formatted(certificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", equalTo(certificateArn));

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s"}
                """.formatted(certificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.Status", equalTo("REVOKED"));
    }

    @Test
    @Order(43)
    void renewPrivateCertificate() {
        String certificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "renewable-private.example.com",
                    "CertificateAuthorityArn": "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/test"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        given()
            .header("X-Amz-Target", "CertificateManager.RenewCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "%s"}
                """.formatted(certificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(equalTo("{}"));
    }

    @Test
    @Order(44)
    void renewCertificateNotFound() {
        given()
            .header("X-Amz-Target", "CertificateManager.RenewCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {"CertificateArn": "arn:aws:acm:us-east-1:123456789012:certificate/nonexistent"}
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

    private static JsonPath getCertificatePems(String certificateArn) {
        return given()
            .header("X-Amz-Target", "CertificateManager.GetCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(certificateArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    /**
     * Certificate is a TLS server leaf, CertificateChain is exactly the local CA served at
     * /_floci/ca.pem, and the pair builds a PKIX path with that CA as the only trust anchor: what a
     * client that pins Certificate plus CertificateChain does, so a signature check alone is not enough.
     */
    private static X509Certificate assertLeafChainsToLocalCa(String leafPem, String chainPem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf = parse(factory, leafPem);
        X509Certificate chain = parse(factory, chainPem);
        String servedCa = given().when().get("/_floci/ca.pem").then().statusCode(200).extract().asString();

        assertEquals(servedCa.strip(), chainPem.strip(), "CertificateChain must be the local CA PEM");
        assertEquals(chain.getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertEquals(-1, leaf.getBasicConstraints(), "a leaf, not a CA");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), leaf.getExtendedKeyUsage(),
                "serverAuth and clientAuth, as DescribeCertificate advertises");

        PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(chain, null)));
        params.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(List.of(leaf)), params);
        return leaf;
    }

    private static X509Certificate parse(CertificateFactory factory, String pem) throws Exception {
        return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
}
