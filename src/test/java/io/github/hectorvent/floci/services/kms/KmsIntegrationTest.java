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
}
