package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class KmsIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void generateMacAndVerifyMacRoundTripThroughJsonHandler() {
        String keyId = given()
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "Description": "integration-hmac",
                    "KeyUsage": "GENERATE_VERIFY_MAC",
                    "CustomerMasterKeySpec": "HMAC_256"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyMetadata.KeyId", notNullValue())
            .body("KeyMetadata.Arn", startsWith("arn:aws:kms:"))
            .body("KeyMetadata.KeyUsage", equalTo("GENERATE_VERIFY_MAC"))
            .body("KeyMetadata.CustomerMasterKeySpec", equalTo("HMAC_256"))
            .extract().jsonPath().getString("KeyMetadata.KeyId");

        String message = Base64.getEncoder().encodeToString(
                "kms integration mac message".getBytes(StandardCharsets.UTF_8));
        String mac = given()
            .header("X-Amz-Target", "TrentService.GenerateMac")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "KeyId": "%s",
                    "Message": "%s",
                    "MacAlgorithm": "HMAC_SHA_256"
                }
                """.formatted(keyId, message))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyId", startsWith("arn:aws:kms:"))
            .body("Mac", notNullValue())
            .body("MacAlgorithm", equalTo("HMAC_SHA_256"))
            .extract().jsonPath().getString("Mac");

        assertEquals(32, Base64.getDecoder().decode(mac).length);

        given()
            .header("X-Amz-Target", "TrentService.VerifyMac")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "KeyId": "%s",
                    "Message": "%s",
                    "Mac": "%s",
                    "MacAlgorithm": "HMAC_SHA_256"
                }
                """.formatted(keyId, message, mac))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyId", startsWith("arn:aws:kms:"))
            .body("MacAlgorithm", equalTo("HMAC_SHA_256"))
            .body("MacValid", equalTo(true));

        String differentMessage = Base64.getEncoder().encodeToString(
                "different message".getBytes(StandardCharsets.UTF_8));

        given()
            .header("X-Amz-Target", "TrentService.VerifyMac")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "KeyId": "%s",
                    "Message": "%s",
                    "Mac": "%s",
                    "MacAlgorithm": "HMAC_SHA_256"
                }
                """.formatted(keyId, differentMessage, mac))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("KMSInvalidMacException"));
    }

    @Test
    void generateRandomReturnsBase64Plaintext() {
        // RED phase: This test is expected to fail until GenerateRandom is wired
        // in KmsJsonHandler.handle(). Currently returns 400 UnsupportedOperation.
        String plaintextBase64 = given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 32
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Plaintext", notNullValue())
            .extract().jsonPath().getString("Plaintext");

        assertEquals(32, Base64.getDecoder().decode(plaintextBase64).length);
    }

    @Test
    void generateRandomMissingNumberOfBytesReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void generateRandomZeroBytesReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 0
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void generateRandomNegativeBytesReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": -1
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void generateRandomTooManyBytesReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 1025
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void generateRandomOneByteReturnsSuccess() {
        String plaintextBase64 = given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 1
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Plaintext", notNullValue())
            .extract().jsonPath().getString("Plaintext");

        assertEquals(1, Base64.getDecoder().decode(plaintextBase64).length);
    }

    @Test
    void generateRandomMaxBytesReturnsSuccess() {
        String plaintextBase64 = given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 1024
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Plaintext", notNullValue())
            .extract().jsonPath().getString("Plaintext");

        assertEquals(1024, Base64.getDecoder().decode(plaintextBase64).length);
    }

    @Test
    void generateRandomWithRecipientReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 32,
                    "Recipient": {}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void generateRandomWithCustomKeyStoreIdReturnsError() {
        given()
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "NumberOfBytes": 32,
                    "CustomKeyStoreId": "cks-1234567890"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void rotateKeyOnDemandReturnsKeyId() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Description\":\"rotate-on-demand\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.RotateKeyOnDemand")
                .contentType("application/x-amz-json-1.1")
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("KeyId", equalTo(keyId));
    }
}
