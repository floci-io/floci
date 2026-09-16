package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * DescribeCertificate reports the ValidationDomain the caller asked for on RequestCertificate: it
 * is the suffix of the mailboxes ACM accepts an approval from, so a caller reading it back has to
 * be able to tell whether the request was taken as sent.
 */
@QuarkusTest
class AcmValidationDomainIntegrationTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void requestedValidationDomainIsReflectedPerDomain() {
        String certificateArn = requestCertificate("""
            {
                "DomainName": "probe.example.validation-domain.test",
                "SubjectAlternativeNames": ["www.other.validation-domain.test"],
                "ValidationMethod": "EMAIL",
                "DomainValidationOptions": [
                    {"DomainName": "probe.example.validation-domain.test", "ValidationDomain": "validation-domain.test"},
                    {"DomainName": "www.other.validation-domain.test", "ValidationDomain": "other.validation-domain.test"}
                ]
            }
            """).statusCode(200).extract().jsonPath().getString("CertificateArn");

        describeCertificate(certificateArn)
            .body("Certificate.DomainValidationOptions.find { it.DomainName == 'probe.example.validation-domain.test' }.ValidationDomain",
                Matchers.equalTo("validation-domain.test"))
            .body("Certificate.DomainValidationOptions.find { it.DomainName == 'www.other.validation-domain.test' }.ValidationDomain",
                Matchers.equalTo("other.validation-domain.test"));
    }

    @Test
    void domainWithoutRequestedValidationDomainValidatesAgainstItself() {
        String certificateArn = requestCertificate("""
            {
                "DomainName": "probe.default.validation-domain.test",
                "ValidationMethod": "EMAIL"
            }
            """).statusCode(200).extract().jsonPath().getString("CertificateArn");

        describeCertificate(certificateArn)
            .body("Certificate.DomainValidationOptions[0].ValidationDomain",
                Matchers.equalTo("probe.default.validation-domain.test"));
    }

    @Test
    void validationDomainThatIsNotASuperdomainIsRejected() {
        requestCertificate("""
            {
                "DomainName": "probe.reject.validation-domain.test",
                "ValidationMethod": "EMAIL",
                "DomainValidationOptions": [
                    {"DomainName": "probe.reject.validation-domain.test", "ValidationDomain": "unrelated.example.net"}
                ]
            }
            """)
            .statusCode(400)
            .body("__type", Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    @Test
    void validationDomainForADomainOutsideTheRequestIsRejected() {
        requestCertificate("""
            {
                "DomainName": "probe.unknown.validation-domain.test",
                "ValidationMethod": "EMAIL",
                "DomainValidationOptions": [
                    {"DomainName": "absent.validation-domain.test", "ValidationDomain": "validation-domain.test"}
                ]
            }
            """)
            .statusCode(400)
            .body("__type", Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    @Test
    void entryWithoutAValidationDomainIsRejected() {
        requestCertificate("""
            {
                "DomainName": "probe.incomplete.validation-domain.test",
                "ValidationMethod": "EMAIL",
                "DomainValidationOptions": [
                    {"DomainName": "probe.incomplete.validation-domain.test"}
                ]
            }
            """)
            .statusCode(400)
            .body("__type", Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    @Test
    void entryWithoutADomainNameIsRejected() {
        requestCertificate("""
            {
                "DomainName": "probe.nameless.validation-domain.test",
                "ValidationMethod": "EMAIL",
                "DomainValidationOptions": [
                    {"ValidationDomain": "validation-domain.test"}
                ]
            }
            """)
            .statusCode(400)
            .body("__type", Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    private static io.restassured.response.ValidatableResponse requestCertificate(String body) {
        return given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static io.restassured.response.ValidatableResponse describeCertificate(String certificateArn) {
        return given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + certificateArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
