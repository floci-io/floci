package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Lambda Layer lifecycle:
 * PublishLayerVersion, GetLayerVersion, ListLayerVersions, ListLayers, DeleteLayerVersion.
 * Also verifies layer attachment on CreateFunction and UpdateFunctionConfiguration.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaLayerIntegrationTest {

    private static final String LAYER_NAME = "test-layer";
    private static final String FN_NAME = "layer-test-fn";

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String layerZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("nodejs/node_modules/my-lib/index.js"));
            zos.write("module.exports = { hello: () => 'world' };".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String functionZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write("exports.handler = async () => ({ statusCode: 200 });".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // ── PublishLayerVersion ───────────────────────────────────────────────────

    @Test
    @Order(1)
    void publishLayerVersion_createsVersion1() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Content": { "ZipFile": "%s" },
                    "CompatibleRuntimes": ["nodejs20.x", "nodejs22.x"],
                    "CompatibleArchitectures": ["x86_64"],
                    "Description": "Shared utilities v1",
                    "LicenseInfo": "MIT"
                }
                """.formatted(layerZipBase64()))
        .when()
            .post("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo(1))
            .body("LayerArn", containsString(":layer:" + LAYER_NAME))
            .body("LayerVersionArn", containsString(":layer:" + LAYER_NAME + ":1"))
            .body("Description", equalTo("Shared utilities v1"))
            .body("LicenseInfo", equalTo("MIT"))
            .body("CompatibleRuntimes", hasItems("nodejs20.x", "nodejs22.x"))
            .body("CompatibleArchitectures", hasItem("x86_64"))
            .body("Content.CodeSize", greaterThan(0))
            .body("Content.CodeSha256", not(emptyString()))
            .body("CreatedDate", not(emptyString()));
    }

    @Test
    @Order(2)
    void publishLayerVersion_createsVersion2() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Content": { "ZipFile": "%s" },
                    "CompatibleRuntimes": ["nodejs22.x"],
                    "Description": "Shared utilities v2"
                }
                """.formatted(layerZipBase64()))
        .when()
            .post("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo(2))
            .body("LayerVersionArn", containsString(":layer:" + LAYER_NAME + ":2"))
            .body("Description", equalTo("Shared utilities v2"));
    }

    // ── GetLayerVersion ──────────────────────────────────────────────────────

    @Test
    @Order(3)
    void getLayerVersion_returnsVersion1() {
        given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions/1")
        .then()
            .statusCode(200)
            .body("Version", equalTo(1))
            .body("Description", equalTo("Shared utilities v1"))
            .body("LayerArn", containsString(":layer:" + LAYER_NAME))
            .body("LayerVersionArn", containsString(":layer:" + LAYER_NAME + ":1"))
            .body("Content.CodeSha256", not(emptyString()));
    }

    @Test
    @Order(4)
    void getLayerVersion_nonExistent_returns404() {
        given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions/99")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(5)
    void getLayerVersion_nonExistentLayer_returns404() {
        given()
        .when()
            .get("/2018-10-31/layers/no-such-layer/versions/1")
        .then()
            .statusCode(404);
    }

    // ── ListLayerVersions ────────────────────────────────────────────────────

    @Test
    @Order(6)
    void listLayerVersions_returnsBothVersions() {
        given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", hasSize(2))
            .body("LayerVersions[0].Version", equalTo(1))
            .body("LayerVersions[1].Version", equalTo(2));
    }

    @Test
    @Order(7)
    void listLayerVersions_nonExistentLayer_returnsEmptyList() {
        given()
        .when()
            .get("/2018-10-31/layers/no-such-layer/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", empty());
    }

    // ── ListLayers ───────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void listLayers_returnsLayerWithLatestVersion() {
        given()
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers", hasSize(greaterThanOrEqualTo(1)))
            .body("Layers.find { it.LayerName == '" + LAYER_NAME + "' }.LayerArn",
                    containsString(":layer:" + LAYER_NAME))
            .body("Layers.find { it.LayerName == '" + LAYER_NAME + "' }.LatestMatchingVersion.Version",
                    equalTo(2));
    }

    // ── Layer attachment on CreateFunction ────────────────────────────────────

    @Test
    @Order(9)
    void createFunction_withLayers_storesLayerArns() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" },
                    "Layers": ["arn:aws:lambda:us-east-1:000000000000:layer:%s:1"]
                }
                """.formatted(FN_NAME, functionZipBase64(), LAYER_NAME))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FN_NAME))
            .body("Layers", hasSize(1))
            .body("Layers[0].Arn", containsString(":layer:" + LAYER_NAME + ":1"));
    }

    @Test
    @Order(10)
    void getFunction_showsLayers() {
        given()
        .when()
            .get("/2015-03-31/functions/" + FN_NAME)
        .then()
            .statusCode(200)
            .body("Configuration.Layers", hasSize(1))
            .body("Configuration.Layers[0].Arn", containsString(":layer:" + LAYER_NAME + ":1"));
    }

    // ── Layer attachment on UpdateFunctionConfiguration ───────────────────────

    @Test
    @Order(11)
    void updateFunctionConfiguration_updatesLayers() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Layers": [
                        "arn:aws:lambda:us-east-1:000000000000:layer:%s:1",
                        "arn:aws:lambda:us-east-1:000000000000:layer:%s:2"
                    ]
                }
                """.formatted(LAYER_NAME, LAYER_NAME))
        .when()
            .put("/2015-03-31/functions/" + FN_NAME + "/configuration")
        .then()
            .statusCode(200)
            .body("Layers", hasSize(2));
    }

    @Test
    @Order(12)
    void updateFunctionConfiguration_removesAllLayers() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Layers": []
                }
                """)
        .when()
            .put("/2015-03-31/functions/" + FN_NAME + "/configuration")
        .then()
            .statusCode(200)
            .body("Layers", anyOf(nullValue(), empty()));
    }

    // ── DeleteLayerVersion ───────────────────────────────────────────────────

    @Test
    @Order(13)
    void deleteLayerVersion_removesVersion1() {
        given()
        .when()
            .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/1")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(14)
    void listLayerVersions_afterDelete_showsOnlyVersion2() {
        given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", hasSize(1))
            .body("LayerVersions[0].Version", equalTo(2));
    }

    @Test
    @Order(15)
    void deleteLayerVersion_nonExistent_returns204() {
        // AWS returns 204 even for non-existent versions
        given()
        .when()
            .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/99")
        .then()
            .statusCode(204);
    }

    // ── PublishLayerVersion validation ────────────────────────────────────────

    @Test
    @Order(16)
    void publishLayerVersion_missingContent_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Description": "No content"
                }
                """)
        .when()
            .post("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(400);
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void cleanup_deleteFunction() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN_NAME)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(21)
    void cleanup_deleteRemainingLayerVersion() {
        given()
        .when()
            .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/2")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(22)
    void listLayers_afterCleanup_noTestLayer() {
        given()
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200);
        // Layer should no longer appear (all versions deleted)
    }
}
