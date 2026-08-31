package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AcmRevokeCertificateGuardTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    CertificateGenerator certificateGenerator;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void revokeCertificateRejectsImportedCertificateWithoutExportEnabled() {
        CertificateGenerator.GeneratedCertificate generated = certificateGenerator.generateCertificate(
            "revoke-guard-imported.example.com", List.of(), KeyAlgorithm.RSA_2048);
        String certJson = generated.certificatePem().replace("\r\n", "\n").replace("\n", "\\n");
        String keyJson = generated.privateKeyPem().replace("\r\n", "\n").replace("\n", "\\n");

        String certificateArn = given()
            .header("X-Amz-Target", "CertificateManager.ImportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"Certificate\":\"" + certJson + "\",\"PrivateKey\":\"" + keyJson + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");

        given()
            .header("X-Amz-Target", "CertificateManager.RevokeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + certificateArn + "\",\"RevocationReason\":\"KEY_COMPROMISE\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
