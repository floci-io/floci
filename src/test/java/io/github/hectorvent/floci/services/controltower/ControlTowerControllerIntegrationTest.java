package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the Control Tower REST-JSON wire in the same order LZA's Prepare stage does. */
@QuarkusTest
class ControlTowerControllerIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listLandingZonesWithEmptyBodyReturnsSeededLandingZone() {
        String authorization = auth("000000000201", EAST);

        Response response = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-landingzones")
                .then()
                .statusCode(200)
                .extract().response();

        List<Map<String, Object>> landingZones = response.path("landingZones");
        assertEquals(1, landingZones.size());
        String arn = (String) landingZones.get(0).get("arn");
        assertNotNull(arn);
        assertTrue(arn.contains("000000000201"));
        assertTrue(arn.endsWith(":landingzone/FLOCISEEDEDLZ1"));
    }

    @Test
    void createLandingZoneReturnsArnAndOperationIdentifier() {
        String authorization = auth("000000000299", "us-west-2");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"version\":\"4.0\",\"tags\":{\"Environment\":\"test\"},"
                        + "\"manifest\":{\"accessManagement\":{\"enabled\":true}}}")
                .when()
                .post("/create-landingzone")
                .then()
                .statusCode(200)
                .body("arn", containsString(":landingzone/"))
                .body("operationIdentifier", containsString("-"));
    }

    @Test
    void getLandingZoneReturnsEveryFieldLzaNonNullAsserts() {
        String authorization = auth("000000000202", EAST);
        String arn = listLandingZonesArn(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"landingZoneIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-landingzone")
                .then()
                .statusCode(200)
                .body("landingZone.arn", equalTo(arn))
                .body("landingZone.status", equalTo("ACTIVE"))
                .body("landingZone.version", equalTo("4.0"))
                .body("landingZone.latestAvailableVersion", equalTo("4.0"))
                .body("landingZone.driftStatus.status", equalTo("IN_SYNC"))
                .body("landingZone.manifest.securityRoles.enabled", equalTo(true))
                .body("landingZone.manifest.accessManagement.enabled", equalTo(true));
    }

    @Test
    void updateLandingZoneRejectsInvalidRemediationTypes() {
        String authorization = auth("000000000203", EAST);
        String arn = listLandingZonesArn(authorization);

        // The model pins remediationTypes to exactly one element with the single
        // valid value INHERITANCE_DRIFT (list min 1 / max 1, enum of one).
        String[] invalidArrays = {
                "[\"FOO\"]",
                "[]",
                "[\"INHERITANCE_DRIFT\", \"INHERITANCE_DRIFT\"]"
        };
        for (String invalid : invalidArrays) {
            given()
                    .contentType("application/json")
                    .header("Authorization", authorization)
                    .body("""
                            {"version":"4.0","landingZoneIdentifier":"%s","remediationTypes":%s,
                             "manifest":{"governedRegions":["us-east-1"],
                               "securityRoles":{"enabled":true,"accountId":"000000000203"},
                               "accessManagement":{"enabled":true}}}
                            """.formatted(arn, invalid))
                    .when()
                    .post("/update-landingzone")
                    .then()
                    .statusCode(400)
                    .body("__type", containsString("ValidationException"));
        }
    }

    @Test
    void updateLandingZoneReconcilesManifestAndOperationSucceeds() {
        String authorization = auth("000000000203", EAST);
        String arn = listLandingZonesArn(authorization);

        String updateBody = """
                {
                  "version": "4.0",
                  "landingZoneIdentifier": "%s",
                  "remediationTypes": ["INHERITANCE_DRIFT"],
                  "manifest": {
                    "governedRegions": ["us-east-1"],
                    "centralizedLogging": {"configurations": {"loggingBucket": {"retentionDays": 90}}},
                    "securityRoles": {"enabled": true, "accountId": "000000000203"},
                    "accessManagement": {"enabled": true}
                  }
                }
                """.formatted(arn);

        String operationIdentifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(updateBody)
                .when()
                .post("/update-landingzone")
                .then()
                .statusCode(200)
                .extract().path("operationIdentifier");
        assertNotNull(operationIdentifier);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationIdentifier + "\"}")
                .when()
                .post("/get-landingzone-operation")
                .then()
                .statusCode(200)
                .body("operationDetails.status", equalTo("SUCCEEDED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"landingZoneIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-landingzone")
                .then()
                .statusCode(200)
                .body("landingZone.manifest.centralizedLogging.configurations.loggingBucket.retentionDays",
                        equalTo(90))
                .body("landingZone.latestAvailableVersion", equalTo("4.0"))
                .body("landingZone.remediationTypes[0]", equalTo("INHERITANCE_DRIFT"));
    }

    @Test
    void deleteLandingZoneReturnsOperationIdentifierAndCanBePolled() {
        String authorization = auth("000000000205", EAST);
        String arn = listLandingZonesArn(authorization);

        String operationIdentifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"landingZoneIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/delete-landingzone")
                .then()
                .statusCode(200)
                .extract().path("operationIdentifier");

        assertNotNull(operationIdentifier);
        assertTrue(operationIdentifier.matches("^[a-f0-9-]{36}$"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationIdentifier + "\"}")
                .when()
                .post("/get-landingzone-operation")
                .then()
                .statusCode(200)
                .body("operationDetails.operationType", equalTo("DELETE"))
                .body("operationDetails.status", equalTo("SUCCEEDED"));
    }

    @Test
    void resetLandingZoneReturnsOperationIdentifierAndCanBePolled() {
        String authorization = auth("000000000206", EAST);
        String arn = listLandingZonesArn(authorization);

        String operationIdentifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"landingZoneIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/reset-landingzone")
                .then()
                .statusCode(200)
                .extract().path("operationIdentifier");

        assertNotNull(operationIdentifier);
        assertTrue(operationIdentifier.matches("^[a-f0-9-]{36}$"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationIdentifier + "\"}")
                .when()
                .post("/get-landingzone-operation")
                .then()
                .statusCode(200)
                .body("operationDetails.operationType", equalTo("RESET"))
                .body("operationDetails.status", equalTo("SUCCEEDED"));
    }

    @Test
    void registerOuFlowListEnableAndPollBaseline() {
        String authorization = auth("000000000204", EAST);
        listLandingZonesArn(authorization);

        Response baselines = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-baselines")
                .then()
                .statusCode(200)
                .extract().response();
        List<Map<String, Object>> baselineList = baselines.path("baselines");
        String ctBaselineArn = baselineList.stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name")))
                .findFirst().orElseThrow().get("arn").toString();
        String identityCenterBaselineArn = baselineList.stream()
                .filter(b -> "IdentityCenterBaseline".equals(b.get("name")))
                .findFirst().orElseThrow().get("arn").toString();

        Response enabledBaselines = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-enabled-baselines")
                .then()
                .statusCode(200)
                .extract().response();
        List<Map<String, Object>> enabledList = enabledBaselines.path("enabledBaselines");
        String identityCenterArn = enabledList.stream()
                .filter(e -> identityCenterBaselineArn.equals(e.get("baselineIdentifier")))
                .findFirst().orElseThrow().get("arn").toString();

        String ouArn = "arn:aws:organizations::000000000204:ou/o-floci0001/ou-abcd-33333333";
        String enableBody = """
                {"baselineIdentifier":"%s","baselineVersion":"5.0","targetIdentifier":"%s",
                 "parameters":[{"key":"IdentityCenterEnabledBaselineArn","value":"%s"}]}
                """.formatted(ctBaselineArn, ouArn, identityCenterArn);

        String operationIdentifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(enableBody)
                .when()
                .post("/enable-baseline")
                .then()
                .statusCode(200)
                .extract().path("operationIdentifier");
        assertNotNull(operationIdentifier);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationIdentifier + "\"}")
                .when()
                .post("/get-baseline-operation")
                .then()
                .statusCode(200)
                .body("baselineOperation.status", equalTo("SUCCEEDED"));

        Response finalList = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-enabled-baselines")
                .then()
                .statusCode(200)
                .extract().response();
        List<Map<String, Object>> finalEnabled = finalList.path("enabledBaselines");
        Map<String, Object> ouEntry = finalEnabled.stream()
                .filter(e -> ouArn.equals(e.get("targetIdentifier")))
                .findFirst().orElseThrow();
        assertEquals("5.0", ouEntry.get("baselineVersion"));
        assertEquals("SUCCEEDED", ((Map<?, ?>) ouEntry.get("statusSummary")).get("status"));
        for (Map<String, Object> entry : finalEnabled) {
            assertNotNull(entry.get("targetIdentifier"));
        }
    }

    @Test
    void updateEnabledBaselineReturnsOperationAndPersistsVersionAndParameters() {
        String authorization = auth("000000000207", EAST);
        Response baselines = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-baselines")
                .then()
                .statusCode(200)
                .extract().response();
        String baselineArn = ((List<Map<String, Object>>) baselines.path("baselines")).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name")))
                .findFirst().orElseThrow().get("arn").toString();
        String targetArn = "arn:aws:organizations::000000000207:ou/o-floci0001/ou-update-00000001";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"baselineIdentifier\":\"" + baselineArn
                        + "\",\"baselineVersion\":\"5.0\",\"targetIdentifier\":\""
                        + targetArn + "\"}")
                .when()
                .post("/enable-baseline")
                .then()
                .statusCode(200);

        Response enabled = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-enabled-baselines")
                .then()
                .statusCode(200)
                .extract().response();
        String enabledArn = ((List<Map<String, Object>>) enabled.path("enabledBaselines")).stream()
                .filter(b -> targetArn.equals(b.get("targetIdentifier")))
                .findFirst().orElseThrow().get("arn").toString();

        String operationIdentifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledBaselineIdentifier\":\"" + enabledArn
                        + "\",\"baselineVersion\":\"6.0\",\"parameters\":[{\"key\":\"Example\",\"value\":{\"enabled\":true}}]}")
                .when()
                .post("/update-enabled-baseline")
                .then()
                .statusCode(200)
                .extract().path("operationIdentifier");
        assertNotNull(operationIdentifier);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationIdentifier + "\"}")
                .when()
                .post("/get-baseline-operation")
                .then()
                .statusCode(200)
                .body("baselineOperation.operationType", equalTo("UPDATE_ENABLED_BASELINE"))
                .body("baselineOperation.status", equalTo("SUCCEEDED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledBaselineIdentifier\":\"" + enabledArn + "\"}")
                .when()
                .post("/get-enabled-baseline")
                .then()
                .statusCode(200)
                .body("enabledBaselineDetails.baselineVersion", equalTo("6.0"))
                .body("enabledBaselineDetails.parameters[0].key", equalTo("Example"))
                .body("enabledBaselineDetails.parameters[0].value.enabled", equalTo(true));
    }

    @Test
    void landingZonesAreIsolatedPerAccount() {
        String authA = auth("000000000205", EAST);
        String authB = auth("000000000206", EAST);

        String arnA = listLandingZonesArn(authA);
        String arnB = listLandingZonesArn(authB);
        assertNotEquals(arnA, arnB);
        assertTrue(arnA.contains("000000000205"));
        assertTrue(arnB.contains("000000000206"));

        String updateBody = """
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true},"marker":"A"}}
                """.formatted(arnA);
        given()
                .contentType("application/json")
                .header("Authorization", authA)
                .body(updateBody)
                .when()
                .post("/update-landingzone")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authB)
                .body("{\"landingZoneIdentifier\":\"" + arnB + "\"}")
                .when()
                .post("/get-landingzone")
                .then()
                .statusCode(200)
                .body("landingZone.manifest.marker", equalTo(null));
    }

    @Test
    void healthReportsControltowerService() {
        given()
                .when()
                .get("/_floci/health")
                .then()
                .statusCode(200)
                .body(containsString("controltower"));
    }

    private static String listLandingZonesArn(String authorization) {
        Response response = given()
                .header("Authorization", authorization)
                .when()
                .post("/list-landingzones")
                .then()
                .statusCode(200)
                .extract().response();
        List<Map<String, Object>> landingZones = response.path("landingZones");
        return (String) landingZones.get(0).get("arn");
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260809/" + region + "/controltower/aws4_request";
    }
}
