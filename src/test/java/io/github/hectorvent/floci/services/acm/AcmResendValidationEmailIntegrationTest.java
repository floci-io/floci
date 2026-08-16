package io.github.hectorvent.floci.services.acm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;

@QuarkusTest
class AcmResendValidationEmailIntegrationTest {

    @Test
    void testResendValidationEmailNotFound() {
        String requestBody = "{\"CertificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/nonexistent\",\"Domain\":\"example.com\",\"ValidationDomain\":\"example.com\"}";

        given()
                .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
                .contentType("application/x-amz-json-1.1")
                .body(requestBody.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/")
                .then()
                .statusCode(404);
    }
}
