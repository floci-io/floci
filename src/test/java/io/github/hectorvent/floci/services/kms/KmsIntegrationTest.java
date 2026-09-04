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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
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
                            "Name": "vellum-tenant-round-trip",
                            "Constraints": {"EncryptionContextEquals": {"tenant_id": "tenant-001"}},
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
                .body("Grants[0].Name", equalTo("vellum-tenant-round-trip"))
                .body("Grants[0].Constraints.EncryptionContextEquals.tenant_id", equalTo("tenant-001"))
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
    void createGrantWithNonObjectConstraintsReturnsValidationException() {
        // The handler previously converted any non-object Constraints (e.g. a raw string or
        // array) to null before it reached KmsService, so a malformed request was silently
        // treated as "no constraints" instead of rejected.
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"constraints-type-check\"}")
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
                            "Operations": ["Encrypt"],
                            "Constraints": "not-an-object"
                        }
                        """.formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
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
            "SYMMETRIC_DEFAULT, SIGN_VERIFY, 400",
            "SYMMETRIC_DEFAULT, GENERATE_VERIFY_MAC, 400",
            "SYMMETRIC_DEFAULT, KEY_AGREEMENT, 400",

            "RSA_2048, ENCRYPT_DECRYPT, 200",
            "RSA_2048, SIGN_VERIFY, 200",
            "RSA_2048, GENERATE_VERIFY_MAC, 400",
            "RSA_2048, KEY_AGREEMENT, 400",

            "RSA_3072, ENCRYPT_DECRYPT, 200",
            "RSA_3072, SIGN_VERIFY, 200",
            "RSA_3072, GENERATE_VERIFY_MAC, 400",
            "RSA_3072, KEY_AGREEMENT, 400",

            "RSA_4096, ENCRYPT_DECRYPT, 200",
            "RSA_4096, SIGN_VERIFY, 200",
            "RSA_4096, GENERATE_VERIFY_MAC, 400",
            "RSA_4096, KEY_AGREEMENT, 400",

            "ECC_NIST_P256, ENCRYPT_DECRYPT, 400",
            "ECC_NIST_P256, SIGN_VERIFY, 200",
            "ECC_NIST_P256, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P256, KEY_AGREEMENT, 200",

            "ECC_NIST_P384, ENCRYPT_DECRYPT, 400",
            "ECC_NIST_P384, SIGN_VERIFY, 200",
            "ECC_NIST_P384, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P384, KEY_AGREEMENT, 200",

            "ECC_NIST_P521, ENCRYPT_DECRYPT, 400",
            "ECC_NIST_P521, SIGN_VERIFY, 200",
            "ECC_NIST_P521, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_P521, KEY_AGREEMENT, 200",

            "ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT, 400",
            "ECC_NIST_EDWARDS25519, SIGN_VERIFY, 200",
            "ECC_NIST_EDWARDS25519, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_EDWARDS25519, KEY_AGREEMENT, 400",

            "ECC_SECG_P256K1, ENCRYPT_DECRYPT, 400",
            "ECC_SECG_P256K1, SIGN_VERIFY, 200",
            "ECC_SECG_P256K1, GENERATE_VERIFY_MAC, 400",
            "ECC_SECG_P256K1, KEY_AGREEMENT, 400",

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

    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, SIGN_VERIFY",
            "RSA_2048, KEY_AGREEMENT",
            "ECC_NIST_P256, ENCRYPT_DECRYPT",
            "ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT",
            "ECC_NIST_EDWARDS25519, KEY_AGREEMENT",
            "ECC_SECG_P256K1, KEY_AGREEMENT"
    })
    void createKeyRejectsIncompatibleKeyUsage(String keySpec, String keyUsage) {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"%s\",\"KeySpec\":\"%s\"}".formatted(keyUsage, keySpec))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "KeyUsage " + keyUsage + " is not compatible with KeySpec " + keySpec + "."));
    }

    /**
     * AWS accepts KEY_AGREEMENT for NIST-standard ECC key specs at CreateKey, even though
     * Floci has no DeriveSharedSecret operation yet. Matching AWS at the CreateKey boundary
     * is deliberate; this guards against an over-broad tightening.
     */
    @Test
    void createKeyAllowsKeyAgreementForNistEccSpecs() {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"KEY_AGREEMENT\",\"KeySpec\":\"ECC_NIST_P256\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeyUsage", equalTo("KEY_AGREEMENT"))
                .body("KeyMetadata.KeySpec", equalTo("ECC_NIST_P256"));
    }

    // ── Issue #1528 — ListKeyPolicies ────────────────────────────────────────

    @Test
    void listKeyPoliciesReturnsDefaultPolicyThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"list-key-policies\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.ListKeyPolicies")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("PolicyNames.size()", equalTo(1))
                .body("PolicyNames[0]", equalTo("default"))
                .body("Truncated", equalTo(false))
                // Truncated is always false, so NextMarker must be absent rather than null.
                .body("$", not(hasKey("NextMarker")));
    }

    @Test
    void listKeyPoliciesIgnoresLimitAndMarker() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"list-key-policies-paging\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("KeyMetadata.KeyId");

        // A single policy name cannot be paginated, and ListKeyPolicies does not declare
        // InvalidMarkerException, so both parameters are accepted without error.
        given()
                .header("X-Amz-Target", "TrentService.ListKeyPolicies")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {"KeyId":"%s","Limit":1,"Marker":"anything"}
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("PolicyNames[0]", equalTo("default"))
                .body("Truncated", equalTo(false));
    }

    @Test
    void listKeyPoliciesReturnsNotFoundForUnknownKey() {
        // Asserting the error type only. Floci returns 404 where real KMS uses 400 for
        // NotFoundException, a pre-existing service-wide deviation that is not this change's to fix.
        given()
                .header("X-Amz-Target", "TrentService.ListKeyPolicies")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"non-existent-id\"}")
                .when()
                .post("/")
                .then()
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void updateAliasRoundTripThroughJsonHandler() {
        String keyId1 = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String keyId2 = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.CreateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-test\",\"TargetKeyId\":\"" + keyId1 + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.UpdateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-test\",\"TargetKeyId\":\"" + keyId2 + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.ListAliases")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId2 + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Aliases.find { it.AliasName == 'alias/update-alias-test' }.TargetKeyId", equalTo(keyId2));
    }

    @Test
    void updateAliasReturnsNotFoundForUnknownAlias() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.UpdateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/non-existent\",\"TargetKeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void updateAliasReturnsNotFoundForUnknownTargetKey() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.CreateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-missing-target\",\"TargetKeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.UpdateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-missing-target\",\"TargetKeyId\":\"non-existent-key\"}")
                .when().post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void updateAliasReturnsInvalidStateForPendingDeletionTarget() {
        String keyId1 = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String keyId2 = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.ScheduleKeyDeletion")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId2 + "\",\"PendingWindowInDays\":7}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.CreateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-pending-deletion\",\"TargetKeyId\":\"" + keyId1 + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.UpdateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-pending-deletion\",\"TargetKeyId\":\"" + keyId2 + "\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"));
    }

    @Test
    void updateAliasReturnsValidationForIncompatibleKeyUsage() {
        String symmetricKeyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String hmacKeyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"GENERATE_VERIFY_MAC\",\"KeySpec\":\"HMAC_256\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.CreateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-incompatible-usage\",\"TargetKeyId\":\"" + symmetricKeyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "TrentService.UpdateAlias")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"AliasName\":\"alias/update-alias-incompatible-usage\",\"TargetKeyId\":\"" + hmacKeyId + "\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    /**
     * Ed25519 keys, checked against what real AWS KMS returns for the same calls.
     *
     * <p>ED25519_SHA_512 takes MessageType RAW and ED25519_PH_SHA_512 takes DIGEST. Real KMS
     * rejects the other pairing, and rejects any other signing algorithm for the key spec. Note
     * that ED25519_PH_SHA_512 pre-hashes the bytes it is given rather than signing them as a
     * digest, so the two algorithms produce different signatures over the same input.
     */
    @Test
    void ed25519KeyIsAnEd25519KeyAndSignsWithBothAlgorithms() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"ECC_NIST_EDWARDS25519\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeySpec", equalTo("ECC_NIST_EDWARDS25519"))
                .body("KeyMetadata.SigningAlgorithms", equalTo(List.of("ED25519_SHA_512", "ED25519_PH_SHA_512")))
                .extract().path("KeyMetadata.KeyId");

        // Real AWS returns a 44 byte SubjectPublicKeyInfo carrying a 32 byte Ed25519 point.
        // A NIST P-521 key, which this used to be, is far larger.
        String publicKey = given()
                .header("X-Amz-Target", "TrentService.GetPublicKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("PublicKey");
        assertEquals(44, Base64.getDecoder().decode(publicKey).length);

        // ED25519_PH_SHA_512 takes one SHA-512 digest, so the two algorithms are given
        // different payloads here the way a caller would send them.
        byte[] raw = "message".getBytes(StandardCharsets.UTF_8);
        String message = Base64.getEncoder().encodeToString(raw);
        String digest = Base64.getEncoder().encodeToString(sha512(raw));
        for (String[] pair : List.of(new String[]{"ED25519_SHA_512", "RAW", message},
                new String[]{"ED25519_PH_SHA_512", "DIGEST", digest})) {
            String signature = given()
                    .header("X-Amz-Target", "TrentService.Sign")
                    .contentType(KMS_CONTENT_TYPE)
                    .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                            .formatted(keyId, pair[2], pair[1], pair[0]))
                    .when().post("/")
                    .then().statusCode(200)
                    .body("SigningAlgorithm", equalTo(pair[0]))
                    .extract().path("Signature");
            assertEquals(64, Base64.getDecoder().decode(signature).length);

            given()
                    .header("X-Amz-Target", "TrentService.Verify")
                    .contentType(KMS_CONTENT_TYPE)
                    .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"%s\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                            .formatted(keyId, pair[2], pair[1], signature, pair[0]))
                    .when().post("/")
                    .then().statusCode(200)
                    .body("SignatureValid", equalTo(true));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ED25519_SHA_512, DIGEST, ValidationException, Message type DIGEST is incompatible with algorithm ED25519_SHA_512.",
            "ED25519_PH_SHA_512, RAW, ValidationException, Message type RAW is incompatible with algorithm ED25519_PH_SHA_512.",
            "ECDSA_SHA_512, RAW, InvalidKeyUsageException, Algorithm ECDSA_SHA_512 is incompatible with key spec ECC_NIST_EDWARDS25519."
    })
    void ed25519RejectsTheCombinationsRealKmsRejects(String algorithm, String messageType, String error, String expectedMessage) {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"ECC_NIST_EDWARDS25519\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"MessageType\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                        .formatted(keyId, messageType, algorithm))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo(error))
                .body("message", equalTo(expectedMessage));
    }

    /**
     * Real KMS checks that a DIGEST for ED25519_PH_SHA_512 is exactly one SHA-512 digest, on
     * Sign and on Verify alike, and answers a wrong length with a ValidationException.
     */
    @ParameterizedTest
    @CsvSource({"TrentService.Sign", "TrentService.Verify"})
    void ed25519PrehashRejectsADigestOfTheWrongLength(String target) {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"ECC_NIST_EDWARDS25519\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        String tooShort = Base64.getEncoder().encodeToString("not a sha-512 digest".getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(new byte[64]);
        given()
                .header("X-Amz-Target", target)
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"DIGEST\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"ED25519_PH_SHA_512\"}"
                        .formatted(keyId, tooShort, signature))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Digest is invalid length for algorithm ED25519_PH_SHA_512."));
    }

    private static byte[] sha512(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-512").digest(value);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Real KMS applies RSAES-OAEP with the key's actual RSA material, so the ciphertext for an
     * RSA_2048 key is exactly 256 bytes of raw RSA output, and both Encrypt and Decrypt echo the
     * EncryptionAlgorithm. Checked against real AWS in us-east-1.
     */
    @Test
    void rsaOaepEncryptDecryptRoundTripThroughJsonHandler() {
        String keyId = createRsaEncryptionKey();
        String plaintext = Base64.getEncoder().encodeToString("secret payload".getBytes(StandardCharsets.UTF_8));

        var encryptResponse = given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(keyId, plaintext))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("EncryptionAlgorithm", equalTo("RSAES_OAEP_SHA_256"))
                .extract().jsonPath();

        String ciphertext = encryptResponse.getString("CiphertextBlob");
        assertEquals(256, Base64.getDecoder().decode(ciphertext).length);

        given()
                .header("X-Amz-Target", "TrentService.Decrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"CiphertextBlob\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(keyId, ciphertext))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Plaintext", equalTo(plaintext))
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("EncryptionAlgorithm", equalTo("RSAES_OAEP_SHA_256"));
    }

    /**
     * The envelope pattern from issue #3024: only the encrypting side holds the public key from
     * GetPublicKey, encrypts locally with RSA-OAEP, and Decrypt accepts that ciphertext. Real
     * AWS returns the plaintext here.
     */
    @Test
    void decryptAcceptsRsaOaepCiphertextMadeWithGetPublicKey() throws Exception {
        String keyId = createRsaEncryptionKey();

        String publicKeyBase64 = given()
                .header("X-Amz-Target", "TrentService.GetPublicKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KeySpec", equalTo("RSA_2048"))
                .body("KeyUsage", equalTo("ENCRYPT_DECRYPT"))
                .body("EncryptionAlgorithms", equalTo(List.of("RSAES_OAEP_SHA_1", "RSAES_OAEP_SHA_256")))
                .extract().path("PublicKey");

        var publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        var cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey, new javax.crypto.spec.OAEPParameterSpec(
                "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource.PSpecified.DEFAULT));
        byte[] localCiphertext = cipher.doFinal("secret payload".getBytes(StandardCharsets.UTF_8));

        given()
                .header("X-Amz-Target", "TrentService.Decrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"CiphertextBlob\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(keyId, Base64.getEncoder().encodeToString(localCiphertext)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Plaintext", equalTo(Base64.getEncoder()
                        .encodeToString("secret payload".getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Real KMS rejects Encrypt on an RSA key when EncryptionAlgorithm is left at its
     * SYMMETRIC_DEFAULT default: InvalidKeyUsageException with this exact message.
     */
    @Test
    void rsaEncryptWithDefaultAlgorithmReturnsInvalidKeyUsage() {
        String keyId = createRsaEncryptionKey();
        String plaintext = Base64.getEncoder().encodeToString("secret payload".getBytes(StandardCharsets.UTF_8));

        given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\"}".formatted(keyId, plaintext))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo("Algorithm SYMMETRIC_DEFAULT is incompatible with key spec RSA_2048."));
    }

    /**
     * The Decrypt-side twin of the test above, with the algorithm left at its default.
     * Real KMS parses the ciphertext before comparing the defaulted SYMMETRIC_DEFAULT
     * algorithm with the named key's spec, so a raw RSA ciphertext answers
     * InvalidCiphertextException, measured against real AWS in us-east-1.
     */
    @Test
    void rsaDecryptWithDefaultAlgorithmReturnsInvalidCiphertext() {
        String keyId = createRsaEncryptionKey();
        String plaintext = Base64.getEncoder().encodeToString("secret payload".getBytes(StandardCharsets.UTF_8));

        String ciphertext = given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(keyId, plaintext))
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CiphertextBlob");

        given()
                .header("X-Amz-Target", "TrentService.Decrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"CiphertextBlob\":\"%s\"}".formatted(keyId, ciphertext))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidCiphertextException"));
    }

    /**
     * ReEncrypt threads SourceEncryptionAlgorithm and DestinationEncryptionAlgorithm
     * independently. Re-wrapping a symmetric ciphertext under an RSA key makes the two
     * response fields differ, which pins the source/destination wiring.
     */
    @Test
    void reEncryptFromSymmetricToRsaEchoesBothAlgorithms() {
        String symmetricKeyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Description\":\"symmetric source\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");
        String rsaKeyId = createRsaEncryptionKey();
        String plaintext = Base64.getEncoder().encodeToString("secret payload".getBytes(StandardCharsets.UTF_8));

        String symmetricCiphertext = given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\"}".formatted(symmetricKeyId, plaintext))
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CiphertextBlob");

        var reEncryptResponse = given()
                .header("X-Amz-Target", "TrentService.ReEncrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"CiphertextBlob\":\"%s\",\"SourceKeyId\":\"%s\",\"DestinationKeyId\":\"%s\","
                        + "\"DestinationEncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}")
                        .formatted(symmetricCiphertext, symmetricKeyId, rsaKeyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("SourceKeyId", startsWith("arn:aws:kms:"))
                .body("SourceEncryptionAlgorithm", equalTo("SYMMETRIC_DEFAULT"))
                .body("DestinationEncryptionAlgorithm", equalTo("RSAES_OAEP_SHA_256"))
                .extract().jsonPath();

        String rsaCiphertext = reEncryptResponse.getString("CiphertextBlob");
        assertEquals(256, Base64.getDecoder().decode(rsaCiphertext).length);

        given()
                .header("X-Amz-Target", "TrentService.Decrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"CiphertextBlob\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(rsaKeyId, rsaCiphertext))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Plaintext", equalTo(plaintext));
    }

    private String createRsaEncryptionKey() {
        return given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"ENCRYPT_DECRYPT\",\"KeySpec\":\"RSA_2048\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");
    }
}
