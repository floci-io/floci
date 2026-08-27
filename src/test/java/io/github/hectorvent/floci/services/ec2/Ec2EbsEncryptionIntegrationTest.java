package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for the EBS account-level encryption defaults via the EC2
 * Query Protocol (form-encoded POST, XML response).
 *
 * <p>Mirrors the call sequence of LZA's {@code Custom::EnableEbsEncryptionByDefault}
 * Lambda: enable + set the default KMS key on create, disable on delete.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2EbsEncryptionIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012";

    @Test
    @Order(1)
    void encryptionByDefaultStartsDisabled() {
        given()
            .formParam("Action", "GetEbsEncryptionByDefault")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("false"));
    }

    @Test
    @Order(2)
    void enableEncryptionByDefault() {
        given()
            .formParam("Action", "EnableEbsEncryptionByDefault")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnableEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("true"));

        given()
            .formParam("Action", "GetEbsEncryptionByDefault")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("true"));
    }

    @Test
    @Order(3)
    void modifyDefaultKmsKeyId() {
        given()
            .formParam("Action", "ModifyEbsDefaultKmsKeyId")
            .formParam("KmsKeyId", KEY_ARN)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyEbsDefaultKmsKeyIdResponse.kmsKeyId", equalTo(KEY_ARN));

        given()
            .formParam("Action", "GetEbsDefaultKmsKeyId")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetEbsDefaultKmsKeyIdResponse.kmsKeyId", equalTo(KEY_ARN));
    }

    @Test
    @Order(4)
    void resetDefaultKmsKeyId() {
        given()
            .formParam("Action", "ResetEbsDefaultKmsKeyId")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResetEbsDefaultKmsKeyIdResponse.kmsKeyId", equalTo("alias/aws/ebs"));
    }

    @Test
    @Order(5)
    void disableEncryptionByDefault() {
        given()
            .formParam("Action", "DisableEbsEncryptionByDefault")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisableEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("false"));
    }
}
