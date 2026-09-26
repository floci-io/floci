package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class KmsIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    KmsService kmsService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void replicateKeyReturnsMultiRegionMetadataThroughJsonHandler() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {
                        "Description": "multi-region-primary",
                        "MultiRegion": true
                    }
                    """)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.MultiRegion", equalTo(true))
                .body("KeyMetadata.KeyId", matchesPattern("mrk-[0-9a-f]{32}"))
                .body("KeyMetadata.MultiRegionConfiguration.MultiRegionKeyType", equalTo("PRIMARY"))
                .extract().path("KeyMetadata.KeyId");

        String replicaKeyArn = given()
                .header("X-Amz-Target", "TrentService.ReplicateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {
                        "KeyId": "%s",
                        "ReplicaRegion": "us-west-2",
                        "Description": "multi-region-replica",
                        "Tags": [{"TagKey":"environment","TagValue":"test"}]
                    }
                    """.formatted(keyId))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("ReplicaKeyMetadata.KeyId", equalTo(keyId))
                .body("ReplicaKeyMetadata.MultiRegion", equalTo(true))
                .body("ReplicaKeyMetadata.MultiRegionConfiguration.MultiRegionKeyType", equalTo("REPLICA"))
                .body("ReplicaKeyMetadata.MultiRegionConfiguration.PrimaryKey.Region", equalTo("us-east-1"))
                .body("ReplicaKeyMetadata.MultiRegionConfiguration.ReplicaKeys[0].Region", equalTo("us-west-2"))
                .body("ReplicaPolicy", notNullValue())
                .body("ReplicaTags[0].TagKey", equalTo("environment"))
                .body("ReplicaTags[0].TagValue", equalTo("test"))
                .extract().path("ReplicaKeyMetadata.Arn");

        String plaintext = Base64.getEncoder().encodeToString(
                "multi-region payload".getBytes(StandardCharsets.UTF_8));
        String ciphertext = given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {
                        "KeyId": "%s",
                        "Plaintext": "%s"
                    }
                    """.formatted(keyId, plaintext))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().path("CiphertextBlob");

        given()
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260922/us-west-2/kms/aws4_request")
                .header("X-Amz-Target", "TrentService.Decrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("""
                    {
                        "KeyId": "%s",
                        "CiphertextBlob": "%s"
                    }
                    """.formatted(keyId, ciphertext))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Plaintext", equalTo(plaintext))
                .body("KeyId", equalTo(replicaKeyArn));
    }

    @Test
    void createKeyWithoutDescriptionReturnsEmptyDescription() {
        String keyId = given()
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(KMS_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyMetadata.Description", equalTo(""))
            .extract().path("KeyMetadata.KeyId");

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
            .body("KeyMetadata.Description", equalTo(""));
    }

    @Test
    void describeKeyReturnsEmptyDescriptionForStoredKeyWithoutOne() {
        String keyId = given()
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(KMS_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("KeyMetadata.KeyId");
        kmsService.describeKey(keyId, "us-east-1").setDescription(null);

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
            .body("KeyMetadata.Description", equalTo(""));
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
            .body("__type", equalTo("KMSInvalidMacException"))
            .body("message", nullValue());
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
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("NumberOfBytes is required."));
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
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '0' at 'numberOfBytes' failed to satisfy "
                    + "constraint: Member must have value greater than or equal to 1"));
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
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '1025' at 'numberOfBytes' failed to satisfy "
                    + "constraint: Member must have value less than or equal to 1024"));
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
                .statusCode(400)
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
                .statusCode(400)
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
                .statusCode(400)
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
            "ML_DSA_44, SIGN_VERIFY, SigningAlgorithms, ML_DSA_SHAKE_256",
            "ML_DSA_65, SIGN_VERIFY, SigningAlgorithms, ML_DSA_SHAKE_256",
            "ML_DSA_87, SIGN_VERIFY, SigningAlgorithms, ML_DSA_SHAKE_256",
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
            "ML_DSA_44, SIGN_VERIFY, 200",
            "ML_DSA_44, GENERATE_VERIFY_MAC, 400",
            "ML_DSA_44, KEY_AGREEMENT, 400",

            "ML_DSA_65, ENCRYPT_DECRYPT, 400",
            "ML_DSA_65, SIGN_VERIFY, 200",
            "ML_DSA_65, GENERATE_VERIFY_MAC, 400",
            "ML_DSA_65, KEY_AGREEMENT, 400",

            "ML_DSA_87, ENCRYPT_DECRYPT, 400",
            "ML_DSA_87, SIGN_VERIFY, 200",
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

    @Test
    void createSm2KeyReportsRegionUnsupportedOperation() {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"SM2\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"))
                .body("message", equalTo("KeySpec SM2 is not supported in this Region"));
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
                .statusCode(400)
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
                .statusCode(400)
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
            "ML_DSA_44, 1334, 2420",
            "ML_DSA_65, 1974, 3309",
            "ML_DSA_87, 2614, 4627"
    })
    void mlDsaKeysCreateSignAndVerify(String keySpec, int publicKeyBytes, int signatureBytes) {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"%s\"}".formatted(keySpec))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeySpec", equalTo(keySpec))
                .body("KeyMetadata.SigningAlgorithms", equalTo(List.of("ML_DSA_SHAKE_256")))
                .extract().path("KeyMetadata.KeyId");

        String publicKey = given()
                .header("X-Amz-Target", "TrentService.GetPublicKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"" + keyId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KeySpec", equalTo(keySpec))
                .body("SigningAlgorithms", equalTo(List.of("ML_DSA_SHAKE_256")))
                .extract().path("PublicKey");
        assertEquals(publicKeyBytes, Base64.getDecoder().decode(publicKey).length);

        String message = Base64.getEncoder().encodeToString("message".getBytes(StandardCharsets.UTF_8));
        String signature = given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"SigningAlgorithm\":\"ML_DSA_SHAKE_256\"}"
                        .formatted(keyId, message))
                .when().post("/")
                .then().statusCode(200)
                .extract().path("Signature");
        assertEquals(signatureBytes, Base64.getDecoder().decode(signature).length);

        given()
                .header("X-Amz-Target", "TrentService.Verify")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"ML_DSA_SHAKE_256\"}"
                        .formatted(keyId, message, signature))
                .when().post("/")
                .then().statusCode(200)
                .body("SignatureValid", equalTo(true));
    }

    @Test
    void mlDsaRejectsDigestMessageType() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"ML_DSA_44\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"MessageType\":\"DIGEST\",\"SigningAlgorithm\":\"ML_DSA_SHAKE_256\"}"
                        .formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Message type DIGEST is incompatible with key spec ML_DSA_44."));
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

    @ParameterizedTest
    @CsvSource({
            "HMAC_256, GENERATE_VERIFY_MAC, Sign",
            "HMAC_256, GENERATE_VERIFY_MAC, Verify",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, Sign",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, Verify",
            "RSA_2048, ENCRYPT_DECRYPT, Sign",
            "RSA_2048, ENCRYPT_DECRYPT, Verify",
            "ECC_NIST_P256, KEY_AGREEMENT, Sign",
            "ECC_NIST_P256, KEY_AGREEMENT, Verify",
    })
    void signAndVerifyRejectKeysWhoseUsageIsNotSignVerify(String keySpec, String keyUsage, String operation) {
        String keyArn = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"%s\",\"KeySpec\":\"%s\"}".formatted(keyUsage, keySpec))
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.Arn");

        String signature = Base64.getEncoder().encodeToString(new byte[64]);
        given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"MessageType\":\"RAW\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"}"
                        .formatted(keyArn, signature))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo(keyArn + " key usage is " + keyUsage + " which is not valid for " + operation + "."));
    }

    @Test
    void signRejectsAnUnknownSigningAlgorithmBeforeLookingUpTheKey() {
        given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"Message\":\"bWVzc2FnZQ==\",\"SigningAlgorithm\":\"FOO\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value 'FOO' at 'signingAlgorithm' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: [RSASSA_PSS_SHA_256, "
                        + "RSASSA_PSS_SHA_384, RSASSA_PSS_SHA_512, RSASSA_PKCS1_V1_5_SHA_256, RSASSA_PKCS1_V1_5_SHA_384, "
                        + "RSASSA_PKCS1_V1_5_SHA_512, ECDSA_SHA_256, ECDSA_SHA_384, ECDSA_SHA_512, ED25519_SHA_512, "
                        + "ED25519_PH_SHA_512, SM2DSA, ML_DSA_SHAKE_256]"));
    }

    @ParameterizedTest
    @CsvSource({"Sign", "Verify"})
    void signAndVerifyRequireASigningAlgorithm(String operation) {
        String keyArn = createKeyArn("RSA_2048", "SIGN_VERIFY");

        given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"%s\"}"
                        .formatted(keyArn, Base64.getEncoder().encodeToString(new byte[64])))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at 'signingAlgorithm' failed to "
                        + "satisfy constraint: Member must not be null"));
    }

    /** SYMMETRIC_DEFAULT is not in the modeled enum, yet KMS answers it with the key spec error. */
    @ParameterizedTest
    @CsvSource({
            "RSA_2048, ECDSA_SHA_256, Sign",
            "RSA_2048, ECDSA_SHA_256, Verify",
            "RSA_2048, SYMMETRIC_DEFAULT, Sign",
            "ECC_NIST_P256, ECDSA_SHA_384, Sign",
            "ECC_NIST_P256, ECDSA_SHA_384, Verify",
            "ECC_NIST_P256, RSASSA_PSS_SHA_256, Sign",
            "ECC_NIST_P256, ED25519_PH_SHA_512, Sign",
    })
    void signAndVerifyRejectAnAlgorithmTheKeySpecDoesNotSupport(String keySpec, String algorithm, String operation) {
        String keyArn = createKeyArn(keySpec, "SIGN_VERIFY");

        given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                        .formatted(keyArn, Base64.getEncoder().encodeToString(new byte[64]), algorithm))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo("Algorithm " + algorithm + " is incompatible with key spec " + keySpec + "."));
    }

    @Test
    void signRejectsAnEncryptionAlgorithmAsASigningAlgorithm() {
        String keyArn = createKeyArn("RSA_2048", "SIGN_VERIFY");

        given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"SigningAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(keyArn))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", startsWith("1 validation error detected: Value 'RSAES_OAEP_SHA_256' at 'signingAlgorithm'"));
    }

    @ParameterizedTest
    @CsvSource({
            "ECC_NIST_P256, ECDSA_SHA_256, 20, Sign",
            "ECC_NIST_P256, ECDSA_SHA_256, 48, Sign",
            "ECC_NIST_P256, ECDSA_SHA_256, 20, Verify",
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_256, 20, Sign",
            "RSA_2048, RSASSA_PSS_SHA_256, 20, Sign",
            "RSA_2048, RSASSA_PSS_SHA_384, 32, Sign",
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_384, 64, Sign",
            "RSA_2048, RSASSA_PSS_SHA_256, 20, Verify",
    })
    void signAndVerifyRejectADigestOfTheWrongLength(String keySpec, String algorithm, int digestBytes,
                                                    String operation) {
        String keyArn = createKeyArn(keySpec, "SIGN_VERIFY");

        given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"DIGEST\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                        .formatted(keyArn, Base64.getEncoder().encodeToString(new byte[digestBytes]),
                                Base64.getEncoder().encodeToString(new byte[64]), algorithm))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Digest is invalid length for algorithm " + algorithm + "."));
    }

    @Test
    void signChecksTheKeySpecBeforeTheDigestLength() {
        String keyArn = createKeyArn("ECC_NIST_P256", "SIGN_VERIFY");

        given()
                .header("X-Amz-Target", "TrentService.Sign")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"DIGEST\",\"SigningAlgorithm\":\"ECDSA_SHA_384\"}"
                        .formatted(keyArn, Base64.getEncoder().encodeToString(new byte[20])))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo("Algorithm ECDSA_SHA_384 is incompatible with key spec ECC_NIST_P256."));
    }

    /** The key state is checked before the algorithm. */
    @ParameterizedTest
    @CsvSource({
            "RSA_2048, SIGN_VERIFY, Sign, RSASSA_PSS_SHA_256",
            "RSA_2048, SIGN_VERIFY, Verify, RSASSA_PSS_SHA_256",
            "RSA_2048, SIGN_VERIFY, Sign, ECDSA_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, GenerateMac, HMAC_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, VerifyMac, HMAC_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, GenerateMac, HMAC_SHA_512",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, Encrypt, SYMMETRIC_DEFAULT",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, GenerateDataKey, SYMMETRIC_DEFAULT",
            "RSA_2048, ENCRYPT_DECRYPT, Encrypt, RSAES_OAEP_SHA_256",
            "RSA_2048, ENCRYPT_DECRYPT, Decrypt, RSAES_OAEP_SHA_256",
    })
    void cryptoOperationsRejectADisabledKey(String keySpec, String keyUsage, String operation, String algorithm) {
        String keyArn = createKeyArn(keySpec, keyUsage);
        callKms("DisableKey", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);

        callKms(operation, cryptoRequest(operation, keyArn, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("DisabledException"))
                .body("message", equalTo(keyArn + " is disabled."));
    }

    /** The key state is checked before the algorithm. */
    @ParameterizedTest
    @CsvSource({
            "ECC_NIST_P256, SIGN_VERIFY, Sign, ECDSA_SHA_256",
            "ECC_NIST_P256, SIGN_VERIFY, Verify, ECDSA_SHA_256",
            "ECC_NIST_P256, SIGN_VERIFY, Sign, ECDSA_SHA_384",
            "HMAC_256, GENERATE_VERIFY_MAC, GenerateMac, HMAC_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, VerifyMac, HMAC_SHA_256",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, Encrypt, SYMMETRIC_DEFAULT",
    })
    void cryptoOperationsRejectAKeyPendingDeletion(String keySpec, String keyUsage, String operation,
                                                   String algorithm) {
        String keyArn = createKeyArn(keySpec, keyUsage);
        callKms("ScheduleKeyDeletion", "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn))
                .then().statusCode(200);

        callKms(operation, cryptoRequest(operation, keyArn, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending deletion."));
    }

    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, Encrypt, SYMMETRIC_DEFAULT",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, GenerateDataKey, SYMMETRIC_DEFAULT",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, GenerateDataKeyWithoutPlaintext, SYMMETRIC_DEFAULT",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, EnableKey, SYMMETRIC_DEFAULT",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, DisableKey, SYMMETRIC_DEFAULT",
            "HMAC_256, GENERATE_VERIFY_MAC, GenerateMac, HMAC_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, VerifyMac, HMAC_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, GenerateMac, HMAC_SHA_512",
    })
    void operationsRejectAKeyPendingImport(String keySpec, String keyUsage, String operation, String algorithm) {
        String keyArn = callKms("CreateKey", "{\"Origin\":\"EXTERNAL\",\"KeySpec\":\"%s\",\"KeyUsage\":\"%s\"}"
                .formatted(keySpec, keyUsage)).then().statusCode(200).extract().path("KeyMetadata.Arn");

        callKms(operation, cryptoRequest(operation, keyArn, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending import."));
    }

    @ParameterizedTest
    @CsvSource({
            "RSA_2048, ENCRYPT_DECRYPT, AWS_KMS",
            "RSA_2048, SIGN_VERIFY, AWS_KMS",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, EXTERNAL",
    })
    void decryptRejectsAKeyIdThatDidNotEncryptTheCiphertext(String keySpec, String keyUsage, String origin) {
        String sourceArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String otherArn = callKms("CreateKey", "{\"Origin\":\"%s\",\"KeySpec\":\"%s\",\"KeyUsage\":\"%s\"}"
                .formatted(origin, keySpec, keyUsage)).then().statusCode(200).extract().path("KeyMetadata.Arn");
        String ciphertext = callKms("Encrypt", "{\"KeyId\":\"%s\",\"Plaintext\":\"aGVsbG8=\"}".formatted(sourceArn))
                .then().statusCode(200).extract().path("CiphertextBlob");

        callKms("Decrypt", "{\"CiphertextBlob\":\"%s\",\"KeyId\":\"%s\"}".formatted(ciphertext, otherArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("IncorrectKeyException"))
                .body("message", equalTo("The key ID in the request does not identify a CMK that can perform this operation."));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GenerateDataKey|\"KeySpec\":\"FOO\"|Value 'FOO' at 'keySpec' failed to satisfy constraint: Member must satisfy enum value set: [RSA_2048, RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384, ECC_NIST_P521, ECC_SECG_P256K1, ECC_NIST_EDWARDS25519, SYMMETRIC_DEFAULT, HMAC_224, HMAC_256, HMAC_384, HMAC_512, SM2, ML_DSA_44, ML_DSA_65, ML_DSA_87]",
            "GenerateDataKey|\"KeySpec\":\"RSA_2048\"|Value 'RSA_2048' at 'keySpec' failed to satisfy constraint: Member must satisfy enum value set: [RSA_2048, RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384, ECC_NIST_P521, ECC_SECG_P256K1, ECC_NIST_EDWARDS25519, SYMMETRIC_DEFAULT, HMAC_224, HMAC_256, HMAC_384, HMAC_512, SM2, ML_DSA_44, ML_DSA_65, ML_DSA_87]",
            "GenerateDataKeyWithoutPlaintext|\"KeySpec\":\"FOO\"|Value 'FOO' at 'keySpec' failed to satisfy constraint: Member must satisfy enum value set: [RSA_2048, RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384, ECC_NIST_P521, ECC_SECG_P256K1, ECC_NIST_EDWARDS25519, SYMMETRIC_DEFAULT, HMAC_224, HMAC_256, HMAC_384, HMAC_512, SM2, ML_DSA_44, ML_DSA_65, ML_DSA_87]",
            "GenerateDataKey|\"NumberOfBytes\":0|Value '0' at 'numberOfBytes' failed to satisfy constraint: Member must have value greater than or equal to 1",
            "GenerateDataKey|\"NumberOfBytes\":1025|Value '1025' at 'numberOfBytes' failed to satisfy constraint: Member must have value less than or equal to 1024",
    })
    void generateDataKeyValidatesItsInputBeforeLookingUpTheKey(String operation, String member, String error) {
        callKms(operation, "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",%s}".formatted(member))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: " + error));
    }

    @ParameterizedTest
    @CsvSource({
            "\"KeySpec\":\"AES_128\", 16",
            "\"KeySpec\":\"AES_256\", 32",
            "\"NumberOfBytes\":7, 7",
    })
    void generateDataKeyReturnsAPlaintextOfTheRequestedLength(String member, int length) {
        String plaintext = callKms("GenerateDataKey", "{\"KeyId\":\"%s\",%s}"
                .formatted(createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT"), member))
                .then().statusCode(200).extract().path("Plaintext");

        assertEquals(length, Base64.getDecoder().decode(plaintext).length);
    }

    @ParameterizedTest
    @CsvSource({"GenerateDataKey", "GenerateDataKeyWithoutPlaintext"})
    void generateDataKeyRejectsAnAsymmetricKey(String operation) {
        callKms(operation, "{\"KeyId\":\"%s\",\"KeySpec\":\"AES_256\"}".formatted(createKeyArn("RSA_2048", "ENCRYPT_DECRYPT")))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo("You cannot generate a data key with an asymmetric CMK"));
    }

    @ParameterizedTest
    @CsvSource({"GenerateDataKey", "GenerateDataKeyWithoutPlaintext"})
    void generateDataKeyNamesItsOperationInTheKeyUsageError(String operation) {
        String keyArn = createKeyArn("ECC_NIST_P256", "SIGN_VERIFY");

        callKms(operation, "{\"KeyId\":\"%s\",\"KeySpec\":\"AES_256\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo(keyArn + " key usage is SIGN_VERIFY which is not valid for " + operation + "."));
    }

    @Test
    void generateDataKeyChecksTheKeyStateBeforeTheKeySpec() {
        String keyArn = createKeyArn("RSA_2048", "ENCRYPT_DECRYPT");
        callKms("DisableKey", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);

        callKms("GenerateDataKey", "{\"KeyId\":\"%s\",\"KeySpec\":\"AES_256\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("DisabledException"))
                .body("message", equalTo(keyArn + " is disabled."));
    }

    /** The key usage and the key state are checked after this. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GenerateDataKey|SYMMETRIC_DEFAULT|ENCRYPT_DECRYPT|",
            "GenerateDataKey|SYMMETRIC_DEFAULT|ENCRYPT_DECRYPT|,\"KeySpec\":\"AES_256\",\"NumberOfBytes\":32",
            "GenerateDataKeyWithoutPlaintext|SYMMETRIC_DEFAULT|ENCRYPT_DECRYPT|",
            "GenerateDataKey|RSA_2048|SIGN_VERIFY|",
            "GenerateDataKey|HMAC_256|GENERATE_VERIFY_MAC|",
    })
    void generateDataKeyNeedsEitherKeySpecOrNumberOfBytes(String operation, String keySpec, String keyUsage,
                                                          String members) {
        String keyArn = createKeyArn(keySpec, keyUsage);
        callKms("DisableKey", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);

        callKms(operation, "{\"KeyId\":\"%s\"%s}".formatted(keyArn, members == null ? "" : members))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Please specify either number of bytes or key spec."));
    }

    @ParameterizedTest
    @CsvSource({"Sign", "Verify"})
    void messageTypeValidationListsTheEnumBeforeLookingUpTheKey(String operation) {
        callKms(operation, ("{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"Message\":\"bWVzc2FnZQ==\","
                + "\"Signature\":\"AAAA\",\"SigningAlgorithm\":\"ECDSA_SHA_256\",\"MessageType\":\"FOO\"}"))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value 'FOO' at 'messageType' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [RAW, DIGEST, EXTERNAL_MU]"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "12345678-1234-1234-1234-123456789012|Key '{arn}12345678-1234-1234-1234-123456789012' does not exist",
            "786CA7CD-B74B-42B3-B887-902AD7AB6429|Key '{arn}786CA7CD-B74B-42B3-B887-902AD7AB6429' does not exist",
            "mrk-1234567812341234123412345678901a|Key '{arn}mrk-1234567812341234123412345678901a' does not exist",
            "{arn}12345678-1234-1234-1234-123456789012|Key '{arn}12345678-1234-1234-1234-123456789012' does not exist",
            "{arn}foo|Invalid keyId foo",
            "foo|Invalid keyId 'foo'",
            "12345678123412341234123456789012|Invalid keyId '12345678123412341234123456789012'",
            "mrk-1234|Invalid keyId 'mrk-1234'",
    })
    void describeKeyNamesAMissingKey(String keyId, String message) {
        String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String arnPrefix = keyArn.substring(0, keyArn.lastIndexOf('/') + 1);

        callKms("DescribeKey", "{\"KeyId\":\"%s\"}".formatted(keyId.replace("{arn}", arnPrefix)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo(message.replace("{arn}", arnPrefix)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DescribeKey|{}|1 validation error detected: Value null at 'keyId' failed to satisfy constraint: Member must not be null",
            "Sign|{\"Message\":\"bWVzc2FnZQ==\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"}|1 validation error detected: Value null at 'keyId' failed to satisfy constraint: Member must not be null",
            "ScheduleKeyDeletion|{}|1 validation error detected: Value null at 'keyId' failed to satisfy constraint: Member must not be null",
            "ReEncrypt|{\"CiphertextBlob\":\"AAAA\"}|1 validation error detected: Value null at 'destinationKeyId' failed to satisfy constraint: Member must not be null",
            "CreateAlias|{\"AliasName\":\"alias/x\"}|1 validation error detected: Value null at 'targetKeyId' failed to satisfy constraint: Member must not be null",
            "GetPublicKey|{\"KeyId\":\"\"}|2 validation errors detected: Value '' at 'keyId' failed to satisfy constraint: Member must have length greater than or equal to 1; Value '' at 'keyId' failed to satisfy constraint: Member must satisfy regular expression pattern: ^\\p{ASCII}+$",
            "DescribeKey|{\"KeyId\":\"\\u30ad\\u30fc\"}|1 validation error detected: Value 'キー' at 'keyId' failed to satisfy constraint: Member must satisfy regular expression pattern: ^\\p{ASCII}+$",
    })
    void operationsValidateTheKeyIdMember(String operation, String body, String message) {
        callKms(operation, body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Encrypt|\"Plaintext\":\"\"|Value at 'plaintext' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "Encrypt|\"Plaintext\":\"{4097}\"|Value at 'plaintext' failed to satisfy constraint: Member must have length less than or equal to 4096",
            "Encrypt||Value at 'plaintext' failed to satisfy constraint: Member must not be null",
            "Sign|\"Message\":\"\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "Sign|\"Message\":\"{4097}\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length less than or equal to 4096",
            "Sign|\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must not be null",
            "Verify|\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'signature' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "Verify|\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"{6145}\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'signature' failed to satisfy constraint: Member must have length less than or equal to 6144",
            "Verify|\"Message\":\"bWVzc2FnZQ==\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value null at 'signature' failed to satisfy constraint: Member must not be null",
            "Verify|\"Message\":\"{4097}\",\"Signature\":\"AAAA\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length less than or equal to 4096",
            "Verify|\"Signature\":\"AAAA\",\"SigningAlgorithm\":\"ECDSA_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must not be null",
            "GenerateMac|\"Message\":\"\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "GenerateMac|\"Message\":\"{4097}\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length less than or equal to 4096",
            "GenerateMac|\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must not be null",
            "VerifyMac|\"Message\":\"bWVzc2FnZQ==\",\"Mac\":\"\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'mac' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "VerifyMac|\"Message\":\"bWVzc2FnZQ==\",\"Mac\":\"{6145}\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'mac' failed to satisfy constraint: Member must have length less than or equal to 6144",
            "VerifyMac|\"Message\":\"bWVzc2FnZQ==\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value null at 'mac' failed to satisfy constraint: Member must not be null",
            "VerifyMac|\"Message\":\"\",\"Mac\":\"AAAA\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "VerifyMac|\"Mac\":\"AAAA\",\"MacAlgorithm\":\"HMAC_SHA_256\"|Value at 'message' failed to satisfy constraint: Member must not be null",
            "Decrypt|\"CiphertextBlob\":\"\"|Value at 'ciphertextBlob' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "Decrypt|\"CiphertextBlob\":\"{6145}\"|Value at 'ciphertextBlob' failed to satisfy constraint: Member must have length less than or equal to 6144",
            "ReEncrypt|\"DestinationKeyId\":\"alias/floci-missing\",\"CiphertextBlob\":\"\"|Value at 'ciphertextBlob' failed to satisfy constraint: Member must have length greater than or equal to 1",
            "ReEncrypt|\"DestinationKeyId\":\"alias/floci-missing\"|Value null at 'ciphertextBlob' failed to satisfy constraint: Member must not be null",
    })
    void blobMembersAreValidatedBeforeTheKeyIsLookedUp(String operation, String members, String error) {
        String blobs = members == null ? "" : members
                .replace("{4097}", Base64.getEncoder().encodeToString(new byte[4097]))
                .replace("{6145}", Base64.getEncoder().encodeToString(new byte[6145]));
        String body = "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\"" + (blobs.isEmpty() ? "" : "," + blobs) + "}";

        callKms(operation, body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: " + error));
    }

    @ParameterizedTest
    @CsvSource({
            "ScheduleKeyDeletion", "CancelKeyDeletion", "EnableKey", "DisableKey", "TagResource", "UntagResource",
            "ListResourceTags", "UpdateKeyDescription", "GetKeyPolicy", "PutKeyPolicy", "ListKeyPolicies",
            "GetKeyRotationStatus", "EnableKeyRotation", "DisableKeyRotation", "RotateKeyOnDemand", "CreateGrant",
            "ListGrants", "RevokeGrant", "GetParametersForImport", "ImportKeyMaterial", "DeleteImportedKeyMaterial",
    })
    void keyOnlyOperationsRejectAnAliasBeforeLookingItUp(String operation) {
        String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String aliasArn = keyArn.substring(0, keyArn.lastIndexOf(':') + 1) + "alias/floci-missing";

        for (String alias : List.of("alias/floci-missing", aliasArn)) {
            callKms(operation, "{\"KeyId\":\"%s\"}".formatted(alias))
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidArnException"))
                    .body("message", equalTo("Key Aliases are not supported for this operation."));
        }
    }

    @Test
    void decryptRequiresACiphertextBlob() {
        callKms("Decrypt", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at 'ciphertextBlob' failed to satisfy "
                        + "constraint: Member must not be null"));
    }

    @ParameterizedTest
    @CsvSource({"key/", "alias/floci-missing"})
    void describeKeyRejectsAnArnFromAnotherRegion(String resource) {
        String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String otherRegion = keyArn.contains(":us-west-2:") ? "eu-west-1" : "us-west-2";
        String foreignArn = keyArn.replaceFirst(":kms:[^:]+:", ":kms:" + otherRegion + ":");
        String requested = "key/".equals(resource)
                ? foreignArn : foreignArn.substring(0, foreignArn.lastIndexOf(':') + 1) + resource;

        callKms("DescribeKey", "{\"KeyId\":\"%s\"}".formatted(requested))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid arn " + otherRegion));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DescribeKey|{\"KeyId\":\"alias/floci-missing\"}|alias/floci-missing",
            "DescribeKey|{\"KeyId\":\"{arn}alias/floci-missing\"}|alias/floci-missing",
            "DescribeKey|{\"KeyId\":\"alias/aws/floci-missing\"}|alias/aws/floci-missing",
            "Encrypt|{\"KeyId\":\"alias/floci-missing\",\"Plaintext\":\"AAAA\"}|alias/floci-missing",
            "UpdateAlias|{\"AliasName\":\"alias/floci-missing\",\"TargetKeyId\":\"{key}\"}|alias/floci-missing",
            "DeleteAlias|{\"AliasName\":\"alias/floci-missing\"}|alias/floci-missing",
    })
    void operationsNameAMissingAlias(String operation, String body, String aliasName) {
        String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String arnPrefix = keyArn.substring(0, keyArn.lastIndexOf(':') + 1);

        callKms(operation, body.replace("{arn}", arnPrefix).replace("{key}", keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Alias " + arnPrefix + aliasName + " is not found."));
    }

    @ParameterizedTest
    @CsvSource({"Encrypt", "Decrypt"})
    void encryptionAlgorithmValidationListsTheEnumInAwsOrder(String operation) {
        callKms(operation, "{\"KeyId\":\"%s\",\"Plaintext\":\"aGVsbG8=\",\"CiphertextBlob\":\"AAAA\",\"EncryptionAlgorithm\":\"FOO\"}"
                .formatted(createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT")))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value 'FOO' at 'encryptionAlgorithm' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: "
                        + "[RSAES_OAEP_SHA_1, RSAES_OAEP_SHA_256, SM2PKE, SYMMETRIC_DEFAULT]"));
    }

    @ParameterizedTest
    @CsvSource({"garbage", "wrong context", "rsa garbage", "rsa short"})
    void decryptRejectsAnInvalidCiphertextWithoutAMessage(String kind) {
        String body = switch (kind) {
            case "garbage" -> "{\"CiphertextBlob\":\"AAAA\"}";
            case "wrong context" -> {
                String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
                String ciphertext = callKms("Encrypt", "{\"KeyId\":\"%s\",\"Plaintext\":\"aGVsbG8=\",\"EncryptionContext\":{\"a\":\"b\"}}"
                        .formatted(keyArn)).then().statusCode(200).extract().path("CiphertextBlob");
                yield "{\"CiphertextBlob\":\"%s\",\"EncryptionContext\":{\"a\":\"c\"}}".formatted(ciphertext);
            }
            default -> {
                byte[] ciphertext = new byte["rsa garbage".equals(kind) ? 256 : 10];
                Arrays.fill(ciphertext, (byte) 1);
                yield "{\"CiphertextBlob\":\"%s\",\"KeyId\":\"%s\",\"EncryptionAlgorithm\":\"RSAES_OAEP_SHA_256\"}"
                        .formatted(Base64.getEncoder().encodeToString(ciphertext), createKeyArn("RSA_2048", "ENCRYPT_DECRYPT"));
            }
        };

        callKms("Decrypt", body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidCiphertextException"))
                .body("message", nullValue());
    }

    @Test
    void reEncryptRejectsADestinationKeyPendingImport() {
        String sourceArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");
        String destinationArn = callKms("CreateKey", "{\"Origin\":\"EXTERNAL\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
        String ciphertext = callKms("Encrypt", "{\"KeyId\":\"%s\",\"Plaintext\":\"aGVsbG8=\"}".formatted(sourceArn))
                .then().statusCode(200).extract().path("CiphertextBlob");

        callKms("ReEncrypt", "{\"CiphertextBlob\":\"%s\",\"DestinationKeyId\":\"%s\"}".formatted(ciphertext, destinationArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(destinationArn + " is pending import."));
    }

    @ParameterizedTest
    @CsvSource({"Decrypt, false", "Decrypt, true", "ReEncrypt, false"})
    void ciphertextOfDeletedKeyMaterialReportsPendingImport(String operation, boolean withKeyId) throws Exception {
        String keyId = createExternalSymmetricKey();
        importFreshMaterial(keyId);
        String keyArn = describeKey(keyId).extract().path("KeyMetadata.Arn");
        String ciphertext = callKms("Encrypt", "{\"KeyId\":\"%s\",\"Plaintext\":\"aGVsbG8=\"}".formatted(keyArn))
                .then().statusCode(200).extract().path("CiphertextBlob");
        callKms("DeleteImportedKeyMaterial", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);
        String body = "ReEncrypt".equals(operation)
                ? "{\"CiphertextBlob\":\"%s\",\"DestinationKeyId\":\"%s\"}"
                        .formatted(ciphertext, createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT"))
                : withKeyId
                        ? "{\"CiphertextBlob\":\"%s\",\"KeyId\":\"%s\"}".formatted(ciphertext, keyArn)
                        : "{\"CiphertextBlob\":\"%s\"}".formatted(ciphertext);

        callKms(operation, body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending import."));
    }

    @ParameterizedTest
    @CsvSource({
            "EnableKey, AWS_KMS",
            "DisableKey, AWS_KMS",
            "UpdateKeyDescription, AWS_KMS",
            "TagResource, AWS_KMS",
            "UntagResource, AWS_KMS",
            "CreateGrant, AWS_KMS",
            "ScheduleKeyDeletion, AWS_KMS",
            "CreateAlias, AWS_KMS",
            "UpdateAlias, AWS_KMS",
            "GetParametersForImport, EXTERNAL",
            "ImportKeyMaterial, EXTERNAL",
    })
    void keyManagementRejectsAKeyPendingDeletion(String operation, String origin) {
        String keyArn = callKms("CreateKey", "{\"Origin\":\"%s\"}".formatted(origin))
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
        String request = keyManagementRequest(operation, keyArn);
        callKms("ScheduleKeyDeletion", "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn))
                .then().statusCode(200);

        callKms(operation, request)
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending deletion."));
    }

    /** The key state is checked before the key spec. */
    @ParameterizedTest
    @CsvSource({
            "ECC_NIST_P256, SIGN_VERIFY, AWS_KMS, is pending deletion.",
            "HMAC_256, GENERATE_VERIFY_MAC, AWS_KMS, is pending deletion.",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, EXTERNAL, is pending import.",
    })
    void getPublicKeyRejectsAKeyPendingDeletionOrImport(String keySpec, String keyUsage, String origin,
                                                        String state) {
        String keyArn = callKms("CreateKey", "{\"Origin\":\"%s\",\"KeySpec\":\"%s\",\"KeyUsage\":\"%s\"}"
                .formatted(origin, keySpec, keyUsage)).then().statusCode(200).extract().path("KeyMetadata.Arn");
        if ("AWS_KMS".equals(origin)) {
            callKms("ScheduleKeyDeletion", "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn))
                    .then().statusCode(200);
        }

        callKms("GetPublicKey", "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " " + state));
    }

    @Test
    void getPublicKeyRejectsASymmetricKeyWithoutAMessage() {
        String keyArn = createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT");

        callKms("GetPublicKey", "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"))
                .body("message", nullValue());
    }

    @Test
    void getPublicKeyWorksOnADisabledKey() {
        String keyArn = createKeyArn("RSA_2048", "SIGN_VERIFY");
        callKms("DisableKey", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);

        callKms("GetPublicKey", "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(200)
                .body("KeyId", equalTo(keyArn));
    }

    /** The key state is checked before the key spec. */
    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, EnableKeyRotation",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, DisableKeyRotation",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, RotateKeyOnDemand",
            "HMAC_256, GENERATE_VERIFY_MAC, EnableKeyRotation",
            "HMAC_256, GENERATE_VERIFY_MAC, DisableKeyRotation",
            "HMAC_256, GENERATE_VERIFY_MAC, RotateKeyOnDemand",
    })
    void rotationRejectsADisabledKey(String keySpec, String keyUsage, String operation) {
        String keyArn = createKeyArn(keySpec, keyUsage);
        callKms("DisableKey", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);

        callKms(operation, "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("DisabledException"))
                .body("message", equalTo(keyArn + " is disabled."));
    }

    /** The key state is checked before the key spec. */
    @ParameterizedTest
    @CsvSource({"EnableKeyRotation", "DisableKeyRotation", "RotateKeyOnDemand"})
    void rotationRejectsAKeyPendingDeletion(String operation) {
        String keyArn = createKeyArn("HMAC_256", "GENERATE_VERIFY_MAC");
        callKms("ScheduleKeyDeletion", "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn))
                .then().statusCode(200);

        callKms(operation, "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending deletion."));
    }

    @ParameterizedTest
    @CsvSource({
            "HMAC_256, GENERATE_VERIFY_MAC, EnableKeyRotation",
            "HMAC_256, GENERATE_VERIFY_MAC, RotateKeyOnDemand",
            "RSA_2048, ENCRYPT_DECRYPT, EnableKeyRotation",
            "RSA_2048, ENCRYPT_DECRYPT, RotateKeyOnDemand",
    })
    void rotationRejectsAKeySpecThatDoesNotRotateWithoutAMessage(String keySpec, String keyUsage, String operation) {
        String keyArn = createKeyArn(keySpec, keyUsage);

        callKms(operation, "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"))
                .body("message", nullValue());
    }

    @ParameterizedTest
    @CsvSource({
            "HMAC_256, GENERATE_VERIFY_MAC",
            "RSA_2048, ENCRYPT_DECRYPT",
            "RSA_2048, SIGN_VERIFY",
            "ECC_NIST_P256, SIGN_VERIFY",
            "ML_DSA_44, SIGN_VERIFY",
    })
    void disableKeyRotationAcceptsAKeySpecThatDoesNotRotate(String keySpec, String keyUsage) {
        String keyArn = createKeyArn(keySpec, keyUsage);

        callKms("DisableKeyRotation", "{\"KeyId\":\"%s\"}".formatted(keyArn)).then().statusCode(200);
    }

    @ParameterizedTest
    @CsvSource({
            "EnableKeyRotation, EXTERNAL",
            "DisableKeyRotation, EXTERNAL",
            "GetParametersForImport, AWS_KMS",
            "ImportKeyMaterial, AWS_KMS",
            "DeleteImportedKeyMaterial, AWS_KMS",
    })
    void operationsRejectAKeyWithTheWrongOrigin(String operation, String origin) {
        String keyArn = callKms("CreateKey", "{\"Origin\":\"%s\"}".formatted(origin))
                .then().statusCode(200).extract().path("KeyMetadata.Arn");

        callKms(operation, keyManagementRequest(operation, keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"))
                .body("message", equalTo(keyArn + " origin is " + origin + " which is not valid for this operation."));
    }

    @Test
    void rotateKeyOnDemandNeedsNewKeyMaterialForAnExternalKey() throws Exception {
        String keyId = createExternalSymmetricKey();
        importFreshMaterial(keyId);
        String keyArn = describeKey(keyId).extract().path("KeyMetadata.Arn");

        callKms("RotateKeyOnDemand", "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo("No available key material pending rotation for the key: " + keyArn + "."));
    }

    /** The key spec is checked before the key material. */
    @Test
    void rotateKeyOnDemandRejectsAnExternalHmacKeyForItsKeySpec() throws Exception {
        String keyId = callKms("CreateKey", "{\"Origin\":\"EXTERNAL\",\"KeySpec\":\"HMAC_256\",\"KeyUsage\":\"GENERATE_VERIFY_MAC\"}")
                .then().statusCode(200).extract().path("KeyMetadata.KeyId");
        importFreshMaterial(keyId);

        callKms("RotateKeyOnDemand", "{\"KeyId\":\"%s\"}".formatted(keyId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"))
                .body("message", nullValue());
    }

    @ParameterizedTest
    @CsvSource({
            "89, greater than or equal to 90",
            "2561, less than or equal to 2560",
    })
    void enableKeyRotationValidatesTheRotationPeriodBeforeLookingUpTheKey(int days, String constraint) {
        callKms("EnableKeyRotation", "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"RotationPeriodInDays\":%d}"
                .formatted(days))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '" + days + "' at 'rotationPeriodInDays' "
                        + "failed to satisfy constraint: Member must have value " + constraint));
    }

    @Test
    void rotateKeyOnDemandRejectsAKeyPendingImport() {
        String keyArn = callKms("CreateKey", "{\"Origin\":\"EXTERNAL\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");

        callKms("RotateKeyOnDemand", "{\"KeyId\":\"%s\"}".formatted(keyArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"))
                .body("message", equalTo(keyArn + " is pending import."));
    }

    private static String keyManagementRequest(String operation, String keyArn) {
        return switch (operation) {
            case "UpdateAlias" -> {
                String aliasName = "alias/pending-deletion-" + keyArn.substring(keyArn.lastIndexOf('/') + 1);
                callKms("CreateAlias", "{\"AliasName\":\"%s\",\"TargetKeyId\":\"%s\"}"
                        .formatted(aliasName, createKeyArn("SYMMETRIC_DEFAULT", "ENCRYPT_DECRYPT")))
                        .then().statusCode(200);
                yield "{\"AliasName\":\"%s\",\"TargetKeyId\":\"%s\"}".formatted(aliasName, keyArn);
            }
            case "GetParametersForImport" ->
                    "{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSAES_OAEP_SHA_256\",\"WrappingKeySpec\":\"RSA_2048\"}"
                            .formatted(keyArn);
            case "ImportKeyMaterial" -> ("{\"KeyId\":\"%s\",\"ImportToken\":\"AAAA\",\"EncryptedKeyMaterial\":\"AAAA\","
                    + "\"ExpirationModel\":\"KEY_MATERIAL_DOES_NOT_EXPIRE\"}").formatted(keyArn);
            case "UpdateKeyDescription" -> "{\"KeyId\":\"%s\",\"Description\":\"x\"}".formatted(keyArn);
            case "TagResource" -> "{\"KeyId\":\"%s\",\"Tags\":[{\"TagKey\":\"a\",\"TagValue\":\"b\"}]}".formatted(keyArn);
            case "UntagResource" -> "{\"KeyId\":\"%s\",\"TagKeys\":[\"a\"]}".formatted(keyArn);
            case "CreateGrant" -> ("{\"KeyId\":\"%s\",\"GranteePrincipal\":\"arn:aws:iam::000000000000:root\","
                    + "\"Operations\":[\"Encrypt\"]}").formatted(keyArn);
            case "ScheduleKeyDeletion" -> "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn);
            case "CreateAlias" -> "{\"AliasName\":\"alias/pending-deletion-%s\",\"TargetKeyId\":\"%s\"}"
                    .formatted(keyArn.substring(keyArn.lastIndexOf('/') + 1), keyArn);
            default -> "{\"KeyId\":\"%s\"}".formatted(keyArn);
        };
    }

    @ParameterizedTest
    @CsvSource({
            "HMAC_256, GENERATE_VERIFY_MAC, DisableKey, Sign, RSASSA_PSS_SHA_256",
            "HMAC_256, GENERATE_VERIFY_MAC, ScheduleKeyDeletion, Sign, RSASSA_PSS_SHA_256",
            "RSA_2048, SIGN_VERIFY, DisableKey, Encrypt, RSAES_OAEP_SHA_256",
    })
    void keyUsageIsCheckedBeforeTheKeyState(String keySpec, String keyUsage, String stateChange, String operation,
                                            String algorithm) {
        String keyArn = createKeyArn(keySpec, keyUsage);
        callKms(stateChange, "{\"KeyId\":\"%s\",\"PendingWindowInDays\":7}".formatted(keyArn)).then().statusCode(200);

        callKms(operation, cryptoRequest(operation, keyArn, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo(keyArn + " key usage is " + keyUsage + " which is not valid for " + operation + "."));
    }

    @ParameterizedTest
    @CsvSource({
            "ECC_NIST_P256, ECDSA_SHA_256, true",
            "ECC_NIST_P256, ECDSA_SHA_256, false",
            "RSA_2048, RSASSA_PSS_SHA_256, true",
            "RSA_2048, RSASSA_PSS_SHA_256, false",
    })
    void verifyRejectsASignatureThatDoesNotMatch(String keySpec, String algorithm, boolean realSignature) {
        String keyArn = createKeyArn(keySpec, "SIGN_VERIFY");
        String signature = realSignature
                ? callKms("Sign", "{\"KeyId\":\"%s\",\"Message\":\"b3RoZXI=\",\"SigningAlgorithm\":\"%s\"}"
                        .formatted(keyArn, algorithm)).then().statusCode(200).extract().path("Signature")
                : "AAAAAAAAAAAAAA==";

        callKms("Verify", "{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                .formatted(keyArn, signature, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidSignatureException"))
                .body("message", nullValue());
    }

    @ParameterizedTest
    @CsvSource({
            "RSA_2048, RSASSA_PSS_SHA_256, RAW, 1, 1",
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_256, RAW, 6144, 97",
            "RSA_2048, RSASSA_PKCS1_V1_5_SHA_256, DIGEST, 6144, 97",
            "ECC_NIST_P256, ECDSA_SHA_256, RAW, 1, 1",
            "ECC_SECG_P256K1, ECDSA_SHA_256, RAW, 1, 1",
            "ECC_SECG_P256K1, ECDSA_SHA_256, RAW, 6144, 97",
            "ECC_NIST_EDWARDS25519, ED25519_SHA_512, RAW, 1, 1",
            "ML_DSA_44, ML_DSA_SHAKE_256, RAW, 6144, 97",
    })
    void verifyRejectsAMalformedSignature(String keySpec, String algorithm, String messageType, int length,
                                          byte fill) {
        String keyArn = createKeyArn(keySpec, "SIGN_VERIFY");
        String message = "DIGEST".equals(messageType)
                ? Base64.getEncoder().encodeToString(new byte[32]) : "bWVzc2FnZQ==";
        byte[] signature = new byte[length];
        Arrays.fill(signature, fill);

        callKms("Verify", ("{\"KeyId\":\"%s\",\"Message\":\"%s\",\"MessageType\":\"%s\",\"Signature\":\"%s\","
                + "\"SigningAlgorithm\":\"%s\"}").formatted(keyArn, message, messageType,
                Base64.getEncoder().encodeToString(signature), algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidSignatureException"))
                .body("message", nullValue());
    }

    @ParameterizedTest
    @CsvSource({
            "RSA_2048, SIGN_VERIFY, GenerateMac",
            "RSA_2048, SIGN_VERIFY, VerifyMac",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, GenerateMac",
            "SYMMETRIC_DEFAULT, ENCRYPT_DECRYPT, VerifyMac",
    })
    void macOperationsRejectKeysWhoseUsageIsNotGenerateVerifyMac(String keySpec, String keyUsage, String operation) {
        String keyArn = createKeyArn(keySpec, keyUsage);

        callKms(operation, cryptoRequest(operation, keyArn, "HMAC_SHA_256"))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo(keyArn + " key usage is " + keyUsage + " which is not valid for " + operation + "."));
    }

    @ParameterizedTest
    @CsvSource({"GenerateMac, FOO", "VerifyMac, FOO", "GenerateMac, hmac_sha_256"})
    void macOperationsRejectAnUnknownMacAlgorithmBeforeLookingUpTheKey(String operation, String algorithm) {
        callKms(operation, cryptoRequest(operation, "00000000-0000-0000-0000-000000000000", algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '" + algorithm + "' at 'macAlgorithm' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[HMAC_SHA_384, HMAC_SHA_256, HMAC_SHA_224, HMAC_SHA_512]"));
    }

    @ParameterizedTest
    @CsvSource({"GenerateMac", "VerifyMac"})
    void macOperationsRequireAMacAlgorithm(String operation) {
        callKms(operation, "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"Message\":\"bWVzc2FnZQ==\",\"Mac\":\"%s\"}"
                .formatted(Base64.getEncoder().encodeToString(new byte[32])))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at 'macAlgorithm' failed to "
                        + "satisfy constraint: Member must not be null"));
    }

    @ParameterizedTest
    @CsvSource({"GenerateMac, HMAC_SHA_512", "GenerateMac, HMAC_SHA_224", "VerifyMac, HMAC_SHA_384"})
    void macOperationsRejectAnAlgorithmTheKeySpecDoesNotSupport(String operation, String algorithm) {
        String keyArn = createKeyArn("HMAC_256", "GENERATE_VERIFY_MAC");

        callKms(operation, cryptoRequest(operation, keyArn, algorithm))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidKeyUsageException"))
                .body("message", equalTo("Algorithm " + algorithm + " is incompatible with key spec HMAC_256."));
    }

    private static String cryptoRequest(String operation, String keyArn, String algorithm) {
        String blob = Base64.getEncoder().encodeToString(new byte[64]);
        return switch (operation) {
            case "Sign", "Verify" ->
                    "{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"Signature\":\"%s\",\"SigningAlgorithm\":\"%s\"}"
                            .formatted(keyArn, blob, algorithm);
            case "GenerateMac", "VerifyMac" ->
                    "{\"KeyId\":\"%s\",\"Message\":\"bWVzc2FnZQ==\",\"Mac\":\"%s\",\"MacAlgorithm\":\"%s\"}"
                            .formatted(keyArn, blob, algorithm);
            case "Encrypt" -> "{\"KeyId\":\"%s\",\"Plaintext\":\"bWVzc2FnZQ==\",\"EncryptionAlgorithm\":\"%s\"}"
                    .formatted(keyArn, algorithm);
            case "Decrypt" -> "{\"KeyId\":\"%s\",\"CiphertextBlob\":\"%s\",\"EncryptionAlgorithm\":\"%s\"}"
                    .formatted(keyArn, blob, algorithm);
            case "GenerateDataKey", "GenerateDataKeyWithoutPlaintext" ->
                    "{\"KeyId\":\"%s\",\"KeySpec\":\"AES_256\"}".formatted(keyArn);
            case "EnableKey", "DisableKey" -> "{\"KeyId\":\"%s\"}".formatted(keyArn);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private static Response callKms(String operation, String body) {
        return given()
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    private static String createKeyArn(String keySpec, String keyUsage) {
        return given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"%s\",\"KeySpec\":\"%s\"}".formatted(keyUsage, keySpec))
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.Arn");
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

    @Test
    void importKeyMaterialRoundTripEnablesAnExternalKey() throws Exception {
        String keyId = createExternalSymmetricKey();

        describeKey(keyId)
                .body("KeyMetadata.Origin", equalTo("EXTERNAL"))
                .body("KeyMetadata.KeyState", equalTo("PendingImport"))
                .body("KeyMetadata.Enabled", equalTo(false));

        given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\"}"
                        .formatted(keyId, Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("KMSInvalidStateException"));

        var parameters = given()
                .header("X-Amz-Target", "TrentService.GetParametersForImport")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSAES_OAEP_SHA_256\",\"WrappingKeySpec\":\"RSA_2048\"}"
                        .formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("PublicKey", notNullValue())
                .body("ImportToken", notNullValue())
                .body("ParametersValidTo", notNullValue())
                .extract().jsonPath();

        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 11);
        String wrapped = Base64.getEncoder().encodeToString(
                wrapWithRsaOaepSha256(parameters.getString("PublicKey"), material));

        given()
                .header("X-Amz-Target", "TrentService.ImportKeyMaterial")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"ImportToken\":\"%s\",\"EncryptedKeyMaterial\":\"%s\","
                        + "\"ExpirationModel\":\"KEY_MATERIAL_DOES_NOT_EXPIRE\"}")
                        .formatted(keyId, parameters.getString("ImportToken"), wrapped))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("KeyMaterialId", matchesPattern("[a-f0-9]{64}"));

        describeKey(keyId)
                .body("KeyMetadata.KeyState", equalTo("Enabled"))
                .body("KeyMetadata.Enabled", equalTo(true))
                .body("KeyMetadata.Origin", equalTo("EXTERNAL"))
                .body("KeyMetadata.ExpirationModel", equalTo("KEY_MATERIAL_DOES_NOT_EXPIRE"))
                .body("KeyMetadata", not(hasKey("ValidTo")));

        given()
                .header("X-Amz-Target", "TrentService.Encrypt")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"Plaintext\":\"%s\"}"
                        .formatted(keyId, Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    void deleteImportedKeyMaterialReturnsTheKeyToPendingImport() throws Exception {
        String keyId = createExternalSymmetricKey();
        importFreshMaterial(keyId);

        given()
                .header("X-Amz-Target", "TrentService.DeleteImportedKeyMaterial")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\"}".formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyId", startsWith("arn:aws:kms:"))
                .body("KeyMaterialId", matchesPattern("[a-f0-9]{64}"));

        describeKey(keyId)
                .body("KeyMetadata.KeyState", equalTo("PendingImport"))
                .body("KeyMetadata.Enabled", equalTo(false))
                .body("KeyMetadata", not(hasKey("ExpirationModel")));
    }

    @Test
    void describeKeyReportsAwsKmsOriginForAnOrdinaryKey() {
        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");

        describeKey(keyId)
                .body("KeyMetadata.Origin", equalTo("AWS_KMS"))
                .body("KeyMetadata", not(hasKey("ExpirationModel")))
                .body("KeyMetadata", not(hasKey("ValidTo")));
    }

    @Test
    void rsaImportRoundTripDerivesThePublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair importedKeyPair = generator.generateKeyPair();

        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Origin\":\"EXTERNAL\",\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"RSA_2048\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeyState", equalTo("PendingImport"))
                .extract().path("KeyMetadata.KeyId");

        var parameters = given()
                .header("X-Amz-Target", "TrentService.GetParametersForImport")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSA_AES_KEY_WRAP_SHA_256\","
                        + "\"WrappingKeySpec\":\"RSA_2048\"}").formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        String wrapped = Base64.getEncoder().encodeToString(wrapWithRsaAesSha256(
                parameters.getString("PublicKey"), importedKeyPair.getPrivate().getEncoded()));
        given()
                .header("X-Amz-Target", "TrentService.ImportKeyMaterial")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"ImportToken\":\"%s\",\"EncryptedKeyMaterial\":\"%s\","
                        + "\"ExpirationModel\":\"KEY_MATERIAL_DOES_NOT_EXPIRE\"}")
                        .formatted(keyId, parameters.getString("ImportToken"), wrapped))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMaterialId", matchesPattern("[a-f0-9]{64}"));

        String publicKey = given()
                .header("X-Amz-Target", "TrentService.GetPublicKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\"}".formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeySpec", equalTo("RSA_2048"))
                .body("KeyUsage", equalTo("SIGN_VERIFY"))
                .extract().path("PublicKey");

        assertEquals(Base64.getEncoder().encodeToString(importedKeyPair.getPublic().getEncoded()), publicKey);
        describeKey(keyId)
                .body("KeyMetadata.KeyState", equalTo("Enabled"))
                .body("KeyMetadata.Enabled", equalTo(true));
    }

    @Test
    void eccImportRoundTripDerivesThePublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair importedKeyPair = generator.generateKeyPair();

        String keyId = given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Origin\":\"EXTERNAL\",\"KeyUsage\":\"SIGN_VERIFY\",\"KeySpec\":\"ECC_NIST_P256\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeyState", equalTo("PendingImport"))
                .extract().path("KeyMetadata.KeyId");

        JsonPath parameters = given()
                .header("X-Amz-Target", "TrentService.GetParametersForImport")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSA_AES_KEY_WRAP_SHA_256\","
                        + "\"WrappingKeySpec\":\"RSA_2048\"}").formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        String wrapped = Base64.getEncoder().encodeToString(wrapWithRsaAesSha256(
                parameters.getString("PublicKey"), importedKeyPair.getPrivate().getEncoded()));
        given()
                .header("X-Amz-Target", "TrentService.ImportKeyMaterial")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"ImportToken\":\"%s\",\"EncryptedKeyMaterial\":\"%s\","
                        + "\"ExpirationModel\":\"KEY_MATERIAL_DOES_NOT_EXPIRE\"}")
                        .formatted(keyId, parameters.getString("ImportToken"), wrapped))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMaterialId", matchesPattern("[a-f0-9]{64}"));

        String publicKey = given()
                .header("X-Amz-Target", "TrentService.GetPublicKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\"}".formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeySpec", equalTo("ECC_NIST_P256"))
                .body("KeyUsage", equalTo("SIGN_VERIFY"))
                .extract().path("PublicKey");

        assertEquals(Base64.getEncoder().encodeToString(importedKeyPair.getPublic().getEncoded()), publicKey);
        describeKey(keyId)
                .body("KeyMetadata.KeyState", equalTo("Enabled"))
                .body("KeyMetadata.Enabled", equalTo(true));
    }

    @Test
    void aDeprecatedWrappingAlgorithmIsRejected() {
        String keyId = createExternalSymmetricKey();

        given()
                .header("X-Amz-Target", "TrentService.GetParametersForImport")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSAES_PKCS1_V1_5\",\"WrappingKeySpec\":\"RSA_2048\"}"
                        .formatted(keyId))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"));
    }

    private String createExternalSymmetricKey() {
        return given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"Origin\":\"EXTERNAL\",\"Description\":\"external key\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("KeyMetadata.KeyId");
    }

    private io.restassured.response.ValidatableResponse describeKey(String keyId) {
        return given()
                .header("X-Amz-Target", "TrentService.DescribeKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\"}".formatted(keyId))
                .when().post("/")
                .then().statusCode(200);
    }

    private void importFreshMaterial(String keyId) throws Exception {
        var parameters = given()
                .header("X-Amz-Target", "TrentService.GetParametersForImport")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyId\":\"%s\",\"WrappingAlgorithm\":\"RSAES_OAEP_SHA_256\",\"WrappingKeySpec\":\"RSA_2048\"}"
                        .formatted(keyId))
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();

        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 3);
        String wrapped = Base64.getEncoder().encodeToString(
                wrapWithRsaOaepSha256(parameters.getString("PublicKey"), material));

        given()
                .header("X-Amz-Target", "TrentService.ImportKeyMaterial")
                .contentType(KMS_CONTENT_TYPE)
                .body(("{\"KeyId\":\"%s\",\"ImportToken\":\"%s\",\"EncryptedKeyMaterial\":\"%s\","
                        + "\"ExpirationModel\":\"KEY_MATERIAL_DOES_NOT_EXPIRE\"}")
                        .formatted(keyId, parameters.getString("ImportToken"), wrapped))
                .when().post("/")
                .then().statusCode(200);
    }

    /**
     * Wraps key material exactly as a caller would: the point of the round trip is that the
     * emulator unwraps what a standard RSAES-OAEP-SHA-256 client produces, not a shape of its own.
     */
    private static byte[] wrapWithRsaOaepSha256(String publicKeyEncoded, byte[] material) throws Exception {
        PublicKey wrappingKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyEncoded)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new OAEPParameterSpec("SHA-256", "MGF1",
                new MGF1ParameterSpec("SHA-256"), PSource.PSpecified.DEFAULT));
        return cipher.doFinal(material);
    }

    private static byte[] wrapWithRsaAesSha256(String publicKeyEncoded, byte[] material) throws Exception {
        byte[] aesKeyBytes = new byte[32];
        Arrays.fill(aesKeyBytes, (byte) 23);
        Cipher aesKwp = Cipher.getInstance("AES/KWP/NoPadding");
        aesKwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKeyBytes, "AES"));
        byte[] wrappedMaterial = aesKwp.doFinal(material);
        byte[] wrappedAesKey = wrapWithRsaOaepSha256(publicKeyEncoded, aesKeyBytes);

        byte[] payload = Arrays.copyOf(wrappedAesKey, wrappedAesKey.length + wrappedMaterial.length);
        System.arraycopy(wrappedMaterial, 0, payload, wrappedAesKey.length, wrappedMaterial.length);
        return payload;
    }
}
