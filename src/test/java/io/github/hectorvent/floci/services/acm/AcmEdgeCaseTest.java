package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for edge cases: wildcard domains, max SANs (100), max tags (50).
 */
@QuarkusTest
class AcmEdgeCaseTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String requestCertificate(String domain) {
        return given()
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
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");
    }

    private static Map<String, String> validationRecord(String certificateArn) {
        return given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
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
            .extract().jsonPath()
            .getMap("Certificate.DomainValidationOptions[0].ResourceRecord");
    }

    // ==================== Wildcard Domain Tests ====================

    @Test
    void wildcardDomainAsPrimary() {
        String certificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "*.example.com"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
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
            .body("Certificate.DomainValidationOptions[0].DomainName", equalTo("*.example.com"))
            .body("Certificate.DomainValidationOptions[0].ResourceRecord.Name",
                matchesPattern("^_[0-9a-f]{32}\\.example\\.com\\.$"));
    }

    @Test
    void wildcardDomainAsSan() {
        String certificateArn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "example.com",
                    "SubjectAlternativeNames": ["*.EXAMPLE.com", "www.example.com"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"))
            .extract().jsonPath().getString("CertificateArn");

        var validationOptions = given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
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

        String baseRecordName = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == 'example.com' }.ResourceRecord.Name");
        String baseRecordValue = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == 'example.com' }.ResourceRecord.Value");
        String wildcardRecordName = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == '*.EXAMPLE.com' }.ResourceRecord.Name");
        String wildcardRecordValue = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == '*.EXAMPLE.com' }.ResourceRecord.Value");
        String distinctRecordName = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == 'www.example.com' }.ResourceRecord.Name");
        String distinctRecordValue = validationOptions.getString(
            "Certificate.DomainValidationOptions.find { it.DomainName == 'www.example.com' }.ResourceRecord.Value");

        assertEquals(baseRecordName, wildcardRecordName);
        assertEquals(baseRecordValue, wildcardRecordValue);
        assertNotEquals(baseRecordName, distinctRecordName);
        assertNotEquals(baseRecordValue, distinctRecordValue);
    }

    @Test
    void nestedWildcardDomain() {
        // AWS allows wildcards only at the leftmost position
        String certificateArn = requestCertificate("*.api.example.com");

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
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
            .body("Certificate.DomainValidationOptions[0].ResourceRecord.Name",
                matchesPattern("^_[0-9a-f]{32}\\.api\\.example\\.com\\.$"));
    }

    @Test
    void validationRecordIsStableAcrossCertificates() {
        String domain = "stable-" + UUID.randomUUID() + ".example.com";
        String firstCertificateArn = requestCertificate(domain);
        String secondCertificateArn = requestCertificate(domain);

        assertNotEquals(firstCertificateArn, secondCertificateArn);
        assertEquals(validationRecord(firstCertificateArn), validationRecord(secondCertificateArn));
    }

    // ==================== Max SANs Tests ====================

    @Test
    void exactlyMaxSans() {
        // 100 SANs is the maximum
        List<String> sans = IntStream.range(0, 99)
            .mapToObj(i -> "san" + i + ".example.com")
            .collect(Collectors.toList());

        String sansJson = sans.stream()
            .map(s -> "\"" + s + "\"")
            .collect(Collectors.joining(", ", "[", "]"));

        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "max-sans.example.com",
                    "SubjectAlternativeNames": %s
                }
                """.formatted(sansJson))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", startsWith("arn:aws:acm:"));
    }

    @Test
    void exceedMaxSans() {
        // 101 SANs should fail (primary domain + 100 SANs = 101 total)
        List<String> sans = IntStream.range(0, 101)
            .mapToObj(i -> "san" + i + ".example.com")
            .collect(Collectors.toList());

        String sansJson = sans.stream()
            .map(s -> "\"" + s + "\"")
            .collect(Collectors.joining(", ", "[", "]"));

        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "exceed-sans.example.com",
                    "SubjectAlternativeNames": %s
                }
                """.formatted(sansJson))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== Max Tags Tests ====================

    @Test
    void exactlyMaxTags() {
        // 50 tags is the maximum
        String tagsJson = IntStream.range(0, 50)
            .mapToObj(i -> "{\"Key\": \"Tag" + i + "\", \"Value\": \"Value" + i + "\"}")
            .collect(Collectors.joining(", ", "[", "]"));

        String arn = given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "max-tags-%s.example.com",
                    "Tags": %s
                }
                """.formatted(UUID.randomUUID(), tagsJson))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        // Verify tags were applied
        given()
            .header("X-Amz-Target", "CertificateManager.ListTagsForCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s"
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(50));
    }

    @Test
    void exceedMaxTags() {
        // 51 tags should fail
        String tagsJson = IntStream.range(0, 51)
            .mapToObj(i -> "{\"Key\": \"Tag" + i + "\", \"Value\": \"Value" + i + "\"}")
            .collect(Collectors.joining(", ", "[", "]"));

        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "exceed-tags.example.com",
                    "Tags": %s
                }
                """.formatted(tagsJson))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== Validation Method Tests ====================

    @Test
    void invalidValidationMethodThrowsException() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "invalid-validation.example.com",
                    "ValidationMethod": "INVALID_METHOD"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== Domain Name Validation Tests ====================

    @Test
    void emptyDomainNameFails() {
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

    @Test
    void domainNameTooLong() {
        // Max domain length is 253 characters
        String longDomain = "a".repeat(254) + ".example.com";
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "%s"
                }
                """.formatted(longDomain))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ==================== Tag Validation Tests ====================

    @Test
    void awsPrefixedTagKeyFails() {
        given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "DomainName": "aws-tag.example.com",
                    "Tags": [{"Key": "aws:reserved", "Value": "test"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void emptyTagKeyFails() {
        given()
            .header("X-Amz-Target", "CertificateManager.AddTagsToCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "arn:aws:acm:us-east-1:123456789012:certificate/test",
                    "Tags": [{"Key": "", "Value": "test"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(anyOf(equalTo(400), equalTo(404)));
    }

    // ==================== Key Algorithm Tests ====================

    @Test
    void allKeyAlgorithmsSupported() {
        String[] algorithms = {"RSA_2048", "RSA_3072", "RSA_4096", "EC_prime256v1", "EC_secp384r1", "EC_secp521r1"};

        for (String algo : algorithms) {
            given()
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType(ACM_CONTENT_TYPE)
                .body("""
                    {
                        "DomainName": "%s.example.com",
                        "KeyAlgorithm": "%s"
                    }
                    """.formatted(algo.toLowerCase().replace("_", "-"), algo))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CertificateArn", startsWith("arn:aws:acm:"));
        }
    }
}
