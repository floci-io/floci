package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class KmsIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateKeyDescriptionRoundTripThroughJsonHandler() {
        var key = given()
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "Description": "old description"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyMetadata.KeyId", notNullValue())
            .extract().jsonPath();

        String keyId = key.getString("KeyMetadata.KeyId");
        assertNotNull(keyId);
        List<String> encryptionAlgorithms = key.getList("KeyMetadata.EncryptionAlgorithms");
        assertEquals(List.of("SYMMETRIC_DEFAULT"), encryptionAlgorithms);

        given()
            .header("X-Amz-Target", "TrentService.UpdateKeyDescription")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "KeyId": "%s",
                    "Description": "new description"
                }
                """.formatted(keyId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "TrentService.DescribeKey")
            .contentType(KMS_CONTENT_TYPE)
            .body("""
                {
                    "KeyId": "%s"
                }
                """.formatted(keyId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyMetadata.Description", equalTo("new description"))
            .body("KeyMetadata.EncryptionAlgorithms", equalTo(List.of("SYMMETRIC_DEFAULT")));
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

    @Test
    void disableKeyUpdatesDescribeKeyState() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"disable-key\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.DisableKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.DescribeKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.Enabled", equalTo(false))
                .body("KeyMetadata.KeyState", equalTo("Disabled"));
    }

    @Test
    void enableKeyRestoresKeyState() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"enable-key\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.DisableKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.EnableKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.DescribeKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.Enabled", equalTo(true))
                .body("KeyMetadata.KeyState", equalTo("Enabled"));
    }

    @Test
    void enableKeyOnPendingDeletionKeyFails() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"enable-pending-deletion\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.ScheduleKeyDeletion")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s","PendingWindowInDays":7}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.EnableKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"));
    }

    @Test
    void updateKeyDescriptionRequiresDescription() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"missing-description-update\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.UpdateKeyDescription")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listGrantsReturnsEmptyGrantListThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"list-grants-empty\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(0))
                .body("Truncated", equalTo(false));
    }

    @Test
    void listGrantsReturnsNotFoundForUnknownKey() {
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"non-existent-id\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createGrantAndListGrantsRoundTripThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"create-grant-round-trip\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        String grantId = given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "%s",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "Operations": ["Encrypt", "Decrypt"]
                        }
                        """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("GrantId", notNullValue())
                .body("GrantToken", notNullValue())
                .extract()
                .path("GrantId");

        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1))
                .body("Grants[0].GrantId", equalTo(grantId))
                .body("Grants[0].KeyId", startsWith("arn:aws:kms:"))
                .body("Grants[0].GranteePrincipal", equalTo("arn:aws:iam::000000000000:user/grantee"))
                .body("Grants[0].Operations[0]", equalTo("Encrypt"))
                .body("Grants[0].Operations[1]", equalTo("Decrypt"))
                .body("Truncated", equalTo(false));
    }

    @Test
    void createGrantReturnsValidationForMissingRequiredFields() {
        given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"GranteePrincipal\":\"arn:aws:iam::000000000000:user/grantee\",\"Operations\":[\"Encrypt\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createGrantReturnsNotFoundForUnknownKey() {
        given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "non-existent-id",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "Operations": ["Encrypt"]
                        }
                        """)
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    // ──────────────────────────── Phase 4: Pagination, Filters, ListRetirableGrants ────────────────────────────

    @Test
    void listGrantsSupportsPaginationThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"pagination-key\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        for (int i = 0; i < 3; i++) {
            given()
                    .header("X-Amz-Target", "TrentService.CreateGrant")
                    .contentType(KMS_CONTENT_TYPE)
                    .body("{\"KeyId\":\"" + keyId + "\",\"GranteePrincipal\":\"arn:aws:iam::000000000000:user/grantee\",\"Operations\":[\"Encrypt\"]}")
                    .when().post("/")
                    .then().statusCode(200);
        }

        String nextMarker = given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"Limit\":2}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(2))
                .body("Truncated", equalTo(true))
                .body("NextMarker", notNullValue())
                .extract().path("NextMarker");

        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"Marker\":\"" + nextMarker + "\",\"Limit\":2}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1))
                .body("Truncated", equalTo(false));
    }

    @Test
    void listGrantsReturnsInvalidMarkerForBadMarker() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"bad-marker-key\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"Marker\":\"bad-marker\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidMarkerException"));
    }

    @Test
    void listRetirableGrantsReturnsMatchingGrantsThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"retirable-key\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "%s",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "RetiringPrincipal": "arn:aws:iam::000000000000:role/retirer",
                            "Operations": ["Encrypt"]
                        }
                        """.formatted(keyId))
                .when().post("/")
                .then().statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.ListRetirableGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"RetiringPrincipal\":\"arn:aws:iam::000000000000:role/retirer\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1))
                .body("Grants[0].RetiringPrincipal", equalTo("arn:aws:iam::000000000000:role/retirer"))
                .body("Truncated", equalTo(false));
    }

    // ──────────────────────────── Phase 5: RevokeGrant ────────────────────────────

    @Test
    void createRevokeAndListGrantsRoundTripThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"revoke-round-trip\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String grantId = given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "%s",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "Operations": ["Encrypt", "Decrypt"]
                        }
                        """.formatted(keyId))
                .when().post("/")
                .then().statusCode(200)
                .body("GrantId", notNullValue())
                .body("GrantToken", notNullValue())
                .extract().path("GrantId");

        // Grant is listed before revoke
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1))
                .body("Grants[0].GrantId", equalTo(grantId));

        // Revoke the grant
        given()
                .header("X-Amz-Target", "TrentService.RevokeGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"GrantId\":\"" + grantId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        // Grant is gone after revoke
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(0))
                .body("Truncated", equalTo(false));
    }

    @Test
    void revokeGrantReturnsNotFoundForUnknownGrant() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"revoke-unknown-grant\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.RevokeGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"GrantId\":\"non-existent-grant-id\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void revokeGrantReturnsNotFoundForUnknownKey() {
        given()
                .header("X-Amz-Target", "TrentService.RevokeGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"non-existent-key\",\"GrantId\":\"some-grant-id\"}")
                .when().post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void revokeGrantReturnsValidationForMissingRequiredFields() {
        given()
                .header("X-Amz-Target", "TrentService.RevokeGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"some-key-id\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    // ──────────────────────────── Phase 6: RetireGrant ────────────────────────────

    @Test
    void createRetireByTokenAndListGrantsRoundTripThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"retire-by-token-round-trip\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String grantToken = given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "%s",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "Operations": ["Encrypt", "Decrypt"]
                        }
                        """.formatted(keyId))
                .when().post("/")
                .then().statusCode(200)
                .body("GrantId", notNullValue())
                .body("GrantToken", notNullValue())
                .extract().path("GrantToken");

        // Grant is listed before retire
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1));

        // Retire by grant token
        given()
                .header("X-Amz-Target", "TrentService.RetireGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"GrantToken\":\"" + grantToken + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        // Grant is gone after retire
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(0))
                .body("Truncated", equalTo(false));
    }

    @Test
    void createRetireByKeyAndGrantIdAndListGrantsRoundTripThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"retire-admin-round-trip\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String grantId = given()
                .header("X-Amz-Target", "TrentService.CreateGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                        {
                            "KeyId": "%s",
                            "GranteePrincipal": "arn:aws:iam::000000000000:user/grantee",
                            "Operations": ["Encrypt", "Decrypt"]
                        }
                        """.formatted(keyId))
                .when().post("/")
                .then().statusCode(200)
                .body("GrantId", notNullValue())
                .body("GrantToken", notNullValue())
                .extract().path("GrantId");

        // Grant is listed before retire
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(1));

        // Administrative retire by KeyId + GrantId
        given()
                .header("X-Amz-Target", "TrentService.RetireGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\",\"GrantId\":\"" + grantId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        // Grant is gone after retire
        given()
                .header("X-Amz-Target", "TrentService.ListGrants")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Grants.size()", equalTo(0))
                .body("Truncated", equalTo(false));
    }

    @Test
    void retireGrantReturnsNotFoundForInvalidToken() {
        given()
                .header("X-Amz-Target", "TrentService.RetireGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"GrantToken\":\"invalid-token-value\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void retireGrantReturnsValidationForMissingAllIdentifiers() {
        given()
                .header("X-Amz-Target", "TrentService.RetireGrant")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, EncryptionAlgorithms, SYMMETRIC_DEFAULT",
            "RSA_2048, ENCRYPT_DECRYPT, EncryptionAlgorithms, 'RSAES_OAEP_SHA_1,RSAES_OAEP_SHA_256'",
            "RSA_2048, SIGN_VERIFY, SigningAlgorithms, 'RSASSA_PKCS1_V1_5_SHA_256,RSASSA_PKCS1_V1_5_SHA_384,RSASSA_PKCS1_V1_5_SHA_512,RSASSA_PSS_SHA_256,RSASSA_PSS_SHA_384,RSASSA_PSS_SHA_512'",
            "RSA_3072, ENCRYPT_DECRYPT, EncryptionAlgorithms, 'RSAES_OAEP_SHA_1,RSAES_OAEP_SHA_256'",
            "RSA_3072, SIGN_VERIFY, SigningAlgorithms, 'RSASSA_PKCS1_V1_5_SHA_256,RSASSA_PKCS1_V1_5_SHA_384,RSASSA_PKCS1_V1_5_SHA_512,RSASSA_PSS_SHA_256,RSASSA_PSS_SHA_384,RSASSA_PSS_SHA_512'",
            "RSA_4096, ENCRYPT_DECRYPT, EncryptionAlgorithms, 'RSAES_OAEP_SHA_1,RSAES_OAEP_SHA_256'",
            "RSA_4096, SIGN_VERIFY, SigningAlgorithms, 'RSASSA_PKCS1_V1_5_SHA_256,RSASSA_PKCS1_V1_5_SHA_384,RSASSA_PKCS1_V1_5_SHA_512,RSASSA_PSS_SHA_256,RSASSA_PSS_SHA_384,RSASSA_PSS_SHA_512'",
            "ECC_NIST_P256, SIGN_VERIFY, SigningAlgorithms, ECDSA_SHA_256",
            "ECC_NIST_P384, SIGN_VERIFY, SigningAlgorithms, ECDSA_SHA_384",
            "ECC_NIST_P521, SIGN_VERIFY, SigningAlgorithms, ECDSA_SHA_512",
            "ECC_SECG_P256K1, SIGN_VERIFY, SigningAlgorithms, ECDSA_SHA_256",
            "HMAC_224, GENERATE_VERIFY_MAC, MacAlgorithms, HMAC_SHA_224",
            "HMAC_256, GENERATE_VERIFY_MAC, MacAlgorithms, HMAC_SHA_256",
            "HMAC_384, GENERATE_VERIFY_MAC, MacAlgorithms, HMAC_SHA_384",
            "HMAC_512, GENERATE_VERIFY_MAC, MacAlgorithms, HMAC_SHA_512"
    })
    void createKeyWithAllImplementedCombinations(String keySpec, String keyUsage, String algorithmField, String expectedAlgorithms) {
        List<String> expectedList = List.of(expectedAlgorithms.split(","));
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                {
                    "Description": "test key",
                    "KeyUsage": "%s",
                    "CustomerMasterKeySpec": "%s"
                }
                """.formatted(keyUsage, keySpec))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.%s".formatted(algorithmField), equalTo(expectedList));
    }

    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, 200",
            "SYMMETRIC_DEFAULT, SIGN_VERIFY, 200",
            "SYMMETRIC_DEFAULT, GENERATE_VERIFY_MAC, 400",
            "SYMMETRIC_DEFAULT, KEY_AGREEMENT, 200",

            "RSA_2048, ENCRYPT_DECRYPT, 200",
            "RSA_2048, SIGN_VERIFY, 200",
            "RSA_2048, GENERATE_VERIFY_MAC, 400",
            "RSA_2048, KEY_AGREEMENT, 200",

            "RSA_3072, ENCRYPT_DECRYPT, 200",
            "RSA_3072, SIGN_VERIFY, 200",
            "RSA_3072, GENERATE_VERIFY_MAC, 400",
            "RSA_3072, KEY_AGREEMENT, 200",

            "RSA_4096, ENCRYPT_DECRYPT, 200",
            "RSA_4096, SIGN_VERIFY, 200",
            "RSA_4096, GENERATE_VERIFY_MAC, 400",
            "RSA_4096, KEY_AGREEMENT, 200",

            "ECC_NIST_P256, ENCRYPT_DECRYPT, 200",
            "ECC_NIST_P256, SIGN_VERIFY, 200",
            "ECC_NIST_P256, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P256, KEY_AGREEMENT, 200",

            "ECC_NIST_P384, ENCRYPT_DECRYPT, 200",
            "ECC_NIST_P384, SIGN_VERIFY, 200",
            "ECC_NIST_P384, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P384, KEY_AGREEMENT, 200",

            "ECC_NIST_P521, ENCRYPT_DECRYPT, 200",
            "ECC_NIST_P521, SIGN_VERIFY, 200",
            "ECC_NIST_P521, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P521, KEY_AGREEMENT, 200",

            "ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT, 200",
            "ECC_NIST_EDWARDS25519, SIGN_VERIFY, 200",
            "ECC_NIST_EDWARDS25519, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_EDWARDS25519, KEY_AGREEMENT, 200",

            "ECC_SECG_P256K1, ENCRYPT_DECRYPT, 200",
            "ECC_SECG_P256K1, SIGN_VERIFY, 200",
            "ECC_SECG_P256K1, GENERATE_VERIFY_MAC, 400",
            "ECC_SECG_P256K1, KEY_AGREEMENT, 200",

            "HMAC_224, ENCRYPT_DECRYPT, 400",
            "HMAC_224, SIGN_VERIFY, 400",
            "HMAC_224, GENERATE_VERIFY_MAC, 200",
            "HMAC_224, KEY_AGREEMENT, 400",

            "HMAC_256, ENCRYPT_DECRYPT, 400",
            "HMAC_256, SIGN_VERIFY, 400",
            "HMAC_256, GENERATE_VERIFY_MAC, 200",
            "HMAC_256, KEY_AGREEMENT, 400",

            "HMAC_384, ENCRYPT_DECRYPT, 400",
            "HMAC_384, SIGN_VERIFY, 400",
            "HMAC_384, GENERATE_VERIFY_MAC, 200",
            "HMAC_384, KEY_AGREEMENT, 400",

            "HMAC_512, ENCRYPT_DECRYPT, 400",
            "HMAC_512, SIGN_VERIFY, 400",
            "HMAC_512, GENERATE_VERIFY_MAC, 200",
            "HMAC_512, KEY_AGREEMENT, 400",

            "SM2, ENCRYPT_DECRYPT, 400", // Not implemented
            "SM2, SIGN_VERIFY, 400",
            "SM2, GENERATE_VERIFY_MAC, 400",
            "SM2, KEY_AGREEMENT, 400",

            "ML_DSA_44, ENCRYPT_DECRYPT, 400", // Not implemented
            "ML_DSA_44, SIGN_VERIFY, 400",
            "ML_DSA_44, GENERATE_VERIFY_MAC, 400",
            "ML_DSA_44, KEY_AGREEMENT, 400",

            "ML_DSA_65, ENCRYPT_DECRYPT, 400",
            "ML_DSA_65, SIGN_VERIFY, 400",
            "ML_DSA_65, GENERATE_VERIFY_MAC, 400",
            "ML_DSA_65, KEY_AGREEMENT, 400",

            "ML_DSA_87, ENCRYPT_DECRYPT, 400",
            "ML_DSA_87, SIGN_VERIFY, 400",
            "ML_DSA_87, GENERATE_VERIFY_MAC, 400",
            "ML_DSA_87, KEY_AGREEMENT, 400"
    })
    void createKeyWithCombinations(String keySpec, String keyUsage, int expectedStatusCode) {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                {
                    "Description": "test key",
                    "KeyUsage": "%s",
                    "KeySpec": "%s"
                }
                """.formatted(keyUsage, keySpec))
                .when()
                .post("/")
                .then()
                .statusCode(expectedStatusCode);
    }
}
