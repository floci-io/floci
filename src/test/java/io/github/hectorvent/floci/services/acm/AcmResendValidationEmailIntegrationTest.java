package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class AcmResendValidationEmailIntegrationTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void testResendValidationEmailNotFound() {
        String requestBody = "{\"CertificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/nonexistent\",\"Domain\":\"example.com\",\"ValidationDomain\":\"example.com\"}";

        given()
                .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
                .contentType(ACM_CONTENT_TYPE)
                .body(requestBody)
                .when()
                .post("/")
                .then()
                .statusCode(404);
    }

    @Test
    void testResendValidationEmailRejectsUnrelatedDomain() {
        String certificateArn = given()
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType(ACM_CONTENT_TYPE)
                .body("{\"DomainName\":\"resend-validation.example.com\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("CertificateArn");

        String requestBody = "{\"CertificateArn\":\"" + certificateArn
                + "\",\"Domain\":\"totally-unrelated.example.org\",\"ValidationDomain\":\"totally-unrelated.example.org\"}";

        given()
                .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
                .contentType(ACM_CONTENT_TYPE)
                .body(requestBody)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    @Test
    void testResendValidationEmailRejectsValidationDomainNotASuperdomain() {
        String certificateArn = given()
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType(ACM_CONTENT_TYPE)
                .body("{\"DomainName\":\"site.subdomain.resend-superdomain.example.com\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("CertificateArn");

        String requestBody = "{\"CertificateArn\":\"" + certificateArn
                + "\",\"Domain\":\"site.subdomain.resend-superdomain.example.com\",\"ValidationDomain\":\"unrelated.example.net\"}";

        given()
                .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
                .contentType(ACM_CONTENT_TYPE)
                .body(requestBody)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.equalTo("InvalidDomainValidationOptionsException"));
    }

    @Test
    void testResendValidationEmailAcceptsMatchingDomainAndSuperdomain() {
        String certificateArn = given()
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType(ACM_CONTENT_TYPE)
                .body("{\"DomainName\":\"site.subdomain.resend-ok.example.com\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("CertificateArn");

        String requestBody = "{\"CertificateArn\":\"" + certificateArn
                + "\",\"Domain\":\"site.subdomain.resend-ok.example.com\",\"ValidationDomain\":\"subdomain.resend-ok.example.com\"}";

        given()
                .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
                .contentType(ACM_CONTENT_TYPE)
                .body(requestBody)
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }
}
