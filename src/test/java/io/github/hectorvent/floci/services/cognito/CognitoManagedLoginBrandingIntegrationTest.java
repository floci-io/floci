package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers CreateManagedLoginBranding, DescribeManagedLoginBranding,
 * DescribeManagedLoginBrandingByClient, UpdateManagedLoginBranding and
 * DeleteManagedLoginBranding.
 *
 * <p>Response shapes and error messages were measured against the live Cognito API. In
 * particular {@code Settings} is omitted entirely when the caller supplied none, while
 * {@code Assets} is always returned.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoManagedLoginBrandingIntegrationTest {

    private static String poolId;
    private static String clientId;
    private static String brandingId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPoolAndClient() throws Exception {
        poolId = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "BrandingTestPool"
                }
                """).path("UserPool").path("Id").asText();

        clientId = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "branding-client"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
    }

    @Test
    @Order(2)
    void describeByClientBeforeAnyBrandingExists() {
        cognitoAction("DescribeManagedLoginBrandingByClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, clientId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo(
                        "ManagedLoginBranding for client " + clientId + " does not exist."));
    }

    @Test
    @Order(3)
    void createWithCognitoProvidedValuesOmitsSettings() throws Exception {
        JsonNode branding = cognitoJson("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true
                }
                """.formatted(poolId, clientId)).path("ManagedLoginBranding");

        brandingId = branding.path("ManagedLoginBrandingId").asText();
        assertTrue(branding.path("Settings").isMissingNode(),
                "AWS omits Settings when the caller supplied none");
        assertTrue(branding.path("Assets").isArray(), "Assets is always returned");
        assertEquals(0, branding.path("Assets").size());
        assertTrue(branding.path("UseCognitoProvidedValues").asBoolean());
        assertEquals(poolId, branding.path("UserPoolId").asText());
    }

    @Test
    @Order(4)
    void createRejectsASecondBrandingForTheSameClient() {
        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true
                }
                """.formatted(poolId, clientId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ManagedLoginBrandingExistsException"))
                .body("message", equalTo(
                        "A ManagedLoginBranding already exists for client " + clientId));
    }

    @Test
    @Order(5)
    void describeByIdReturnsTheSameBranding() throws Exception {
        JsonNode branding = cognitoJson("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, brandingId)).path("ManagedLoginBranding");

        assertEquals(brandingId, branding.path("ManagedLoginBrandingId").asText());
        assertTrue(branding.path("Settings").isMissingNode());
    }

    @Test
    @Order(6)
    void describeRejectsAnIdThatIsNotAVersion4Uuid() {
        cognitoAction("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "00000000-0000-0000-0000-000000000000"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(7)
    void describeReportsAWellFormedButUnknownId() {
        cognitoAction("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, UUID.randomUUID()))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("ManagedLoginBranding does not exist."));
    }

    @Test
    @Order(8)
    void updateSetsSettingsAndAssets() throws Exception {
        JsonNode branding = cognitoJson("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "UseCognitoProvidedValues": false,
                  "Settings": {"components": {}},
                  "Assets": [
                    {"Category": "FAVICON_SVG", "ColorMode": "DARK", "Extension": "SVG", "Bytes": "PHN2Zy8+"}
                  ]
                }
                """.formatted(poolId, brandingId)).path("ManagedLoginBranding");

        assertTrue(branding.path("Settings").isObject());
        assertEquals(1, branding.path("Assets").size());
        assertEquals("FAVICON_SVG", branding.path("Assets").get(0).path("Category").asText());

        JsonNode readBack = cognitoJson("DescribeManagedLoginBrandingByClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, clientId)).path("ManagedLoginBranding");
        assertEquals(1, readBack.path("Assets").size());
    }

    @Test
    @Order(9)
    void updateLeavesOmittedMembersAlone() throws Exception {
        // Settings alone is the legal way to touch one member: AWS rejects an update whose
        // only branding member is UseCognitoProvidedValues=false, because false selects no
        // source. See validateBrandingSource for the measured matrix.
        cognitoJson("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "Settings": {"components": {}}
                }
                """.formatted(poolId, brandingId));

        JsonNode branding = cognitoJson("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, brandingId)).path("ManagedLoginBranding");

        assertEquals(1, branding.path("Assets").size(), "omitting Assets must not clear them");
        assertTrue(branding.path("Settings").isObject(), "omitting Settings must not clear it");
    }

    @Test
    @Order(10)
    void createRejectsAnUnknownClient() {
        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "nosuchclientid",
                  "UseCognitoProvidedValues": true
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * Measured against AWS: a create naming neither member is rejected, a wrongly typed Assets
     * is a deserialization failure, and a non-boolean UseCognitoProvidedValues is accepted
     * rather than rejected.
     */
    @Test
    @Order(11)
    void malformedCreateMembersMatchAwsHandling() throws Exception {
        String otherClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "branding-client-2"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, otherClient))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "useCognitoProvidedValues or settings should be specified (but not both)"));

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true,
                  "Assets": "nope"
                }
                """.formatted(poolId, otherClient))
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true,
                  "Assets": [1, 2]
                }
                """.formatted(poolId, otherClient))
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Unexpected value type in payload"));

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true,
                  "Assets": [null]
                }
                """.formatted(poolId, otherClient))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value '[null]' at 'assets' failed to satisfy "
                                + "constraint: Member must satisfy constraint: [Member must not be null]"));

        JsonNode coerced = cognitoJson("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": "yes"
                }
                """.formatted(poolId, otherClient)).path("ManagedLoginBranding");
        assertTrue(coerced.path("ManagedLoginBrandingId").isTextual(),
                "AWS accepts a non-boolean UseCognitoProvidedValues rather than rejecting it");

        cognitoAction("DeleteManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, coerced.path("ManagedLoginBrandingId").asText()))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(12)
    void deleteRemovesTheBranding() {
        cognitoAction("DeleteManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, brandingId))
                .then()
                .statusCode(200);

        cognitoAction("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, brandingId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * A branding request must select exactly one source. Measured against Cognito in
     * ap-southeast-1: naming both, naming neither, and naming only
     * {@code UseCognitoProvidedValues=false} are all rejected with the same message,
     * on create and on update alike.
     */
    @Test
    @Order(13)
    void brandingSourceMustBeExactlyOne() throws Exception {
        String message = "useCognitoProvidedValues or settings should be specified (but not both)";

        String freshClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "branding-source-client"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        // Own the branding this test updates: the shared brandingId is deleted earlier.
        String ownBrandingId = cognitoJson("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true
                }
                """.formatted(poolId, freshClient))
                .path("ManagedLoginBranding").path("ManagedLoginBrandingId").asText();

        String bareClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "branding-source-bare"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": true,
                  "Settings": {"components": {}}
                }
                """.formatted(poolId, bareClient))
                .then().statusCode(400).body("message", equalTo(message));

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "UseCognitoProvidedValues": false
                }
                """.formatted(poolId, bareClient))
                .then().statusCode(400).body("message", equalTo(message));

        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "UseCognitoProvidedValues": true,
                  "Settings": {"components": {}}
                }
                """.formatted(poolId, ownBrandingId))
                .then().statusCode(400).body("message", equalTo(message));

        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, ownBrandingId))
                .then().statusCode(400).body("message", equalTo(message));

        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "Assets": []
                }
                """.formatted(poolId, ownBrandingId))
                .then().statusCode(400).body("message", equalTo(message));

        // false plus settings selects settings, so it is accepted.
        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "UseCognitoProvidedValues": false,
                  "Settings": {"components": {}}
                }
                """.formatted(poolId, ownBrandingId))
                .then().statusCode(200);
    }

    /**
     * Every non-boolean, non-string type in this member is a SerializationException at
     * the service, including alongside otherwise valid Settings. Measured in
     * ap-southeast-1: 1, JSON null, {} and [] are all rejected, while omitting the
     * member entirely is accepted and stores false.
     */
    @Test
    @Order(14)
    void malformedUseCognitoProvidedValuesTypesAreRejected() throws Exception {
        for (String malformed : new String[] {"1", "null", "{}", "[]"}) {
            String client = cognitoJson("CreateUserPoolClient", """
                    {
                      "UserPoolId": "%s",
                      "ClientName": "malformed-flag-%s"
                    }
                    """.formatted(poolId, Integer.toHexString(malformed.hashCode())))
                    .path("UserPoolClient").path("ClientId").asText();

            cognitoAction("CreateManagedLoginBranding", """
                    {
                      "UserPoolId": "%s",
                      "ClientId": "%s",
                      "UseCognitoProvidedValues": %s,
                      "Settings": {"components": {}}
                    }
                    """.formatted(poolId, client, malformed))
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("SerializationException"));
        }

        // Omitting the member is not malformed: settings alone select the source.
        String ok = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "malformed-flag-control"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "Settings": {"components": {}}
                }
                """.formatted(poolId, ok))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(15)
    void createRejectsMoreThanFortyAssets() {
        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, clientId, assetArray(41)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value '" + renderedAssets(41)
                                + "' at 'assets' failed to satisfy constraint: "
                                + "Member must have length less than or equal to 40"));
    }

    @Test
    @Order(16)
    void fortyAssetsAreAccepted() throws Exception {
        String extraClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "forty-asset-client"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        cognitoAction("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, extraClient, assetArray(40)))
                .then()
                .statusCode(200)
                .body("ManagedLoginBranding.Assets.size()", equalTo(40));
    }

    @Test
    @Order(17)
    void updateRejectsMoreThanFortyAssets() {
        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, brandingId, assetArray(41)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value '" + renderedAssets(41)
                                + "' at 'assets' failed to satisfy constraint: "
                                + "Member must have length less than or equal to 40"));
    }

    @Test
    @Order(18)
    void theAssetViolationIsReportedAheadOfTheBrandingIdViolation() {
        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "not-a-uuid",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, assetArray(41)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "2 validation errors detected: Value '" + renderedAssets(41)
                                + "' at 'assets' failed to satisfy constraint: "
                                + "Member must have length less than or equal to 40; "
                                + "Value 'not-a-uuid' at 'managedLoginBrandingId' failed to satisfy "
                                + "constraint: Member must satisfy regular expression pattern: "
                                + BRANDING_ID_PATTERN));
    }

    @Test
    @Order(19)
    void shapeViolationsAreReportedBeforeTheMissingPool() {
        cognitoAction("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "us-east-1_nosuchpool",
                  "ManagedLoginBrandingId": "not-a-uuid"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value 'not-a-uuid' at 'managedLoginBrandingId' "
                                + "failed to satisfy constraint: Member must satisfy regular expression "
                                + "pattern: " + BRANDING_ID_PATTERN));
    }

    @Test
    @Order(20)
    void aRejectedUpdateLeavesTheAssetsAlone() throws Exception {
        String client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "rejected-update-client"
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String branding = cognitoJson("CreateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, client, assetArray(1)))
                .path("ManagedLoginBranding").path("ManagedLoginBrandingId").asText();

        cognitoAction("UpdateManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s",
                  "Settings": {"components": {}},
                  "Assets": %s
                }
                """.formatted(poolId, branding, assetArray(41)))
                .then()
                .statusCode(400);

        JsonNode readBack = cognitoJson("DescribeManagedLoginBranding", """
                {
                  "UserPoolId": "%s",
                  "ManagedLoginBrandingId": "%s"
                }
                """.formatted(poolId, branding)).path("ManagedLoginBranding");
        assertEquals(1, readBack.path("Assets").size(), "a rejected update must not replace the assets");
    }

    @Test
    @Order(21)
    void deletePool() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }

    private static final String BRANDING_ID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[4][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    /** A one-byte payload, so the rendered buffer length stays easy to read. */
    private static final String ASSET_BYTES = "AA==";

    private static String assetArray(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("{\"Category\": \"PAGE_HEADER_LOGO\", \"ColorMode\": \"LIGHT\", ")
                    .append("\"Extension\": \"PNG\", \"Bytes\": \"").append(ASSET_BYTES)
                    .append("\", \"ResourceId\": \"asset-").append(i).append("\"}");
        }
        return json.append("]").toString();
    }

    private static String renderedAssets(int count) {
        StringBuilder rendered = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            rendered.append("AssetType(category=PAGE_HEADER_LOGO, colorMode=LIGHT, extension=PNG, ")
                    .append("bytes=java.nio.HeapByteBuffer[pos=0 lim=1 cap=1], resourceId=asset-")
                    .append(i).append(")");
        }
        return rendered.append("]").toString();
    }
}
