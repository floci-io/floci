package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * GetLayerVersionByArn: GET /2018-10-31/layers?find=LayerVersion&amp;Arn={arn}. Shares
 * ListLayers' path, so these also pin that ListLayers itself is unchanged.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaGetLayerVersionByArnIntegrationTest {

    private static final String LAYER_NAME = "by-arn-layer";

    private static String layerZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("python/shared.py"));
            zos.write("VALUE = 1".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String publishLayer() throws Exception {
        return given()
            .contentType("application/json")
            .body("""
                {
                    "Content": { "ZipFile": "%s" },
                    "CompatibleRuntimes": ["python3.12"],
                    "CompatibleArchitectures": ["x86_64"],
                    "Description": "resolved by arn",
                    "LicenseInfo": "Apache-2.0"
                }
                """.formatted(layerZipBase64()))
        .when()
            .post("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(201)
            .extract().path("LayerVersionArn");
    }

    @Test
    @Order(1)
    void returnsTheLayerVersion_notAListLayersBody() throws Exception {
        String arn = publishLayer();

        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", arn)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            // A LayerVersion object, never the ListLayers envelope.
            .body("Layers", nullValue())
            .body("LayerVersionArn", equalTo(arn))
            .body("LayerArn", containsString(":layer:" + LAYER_NAME))
            .body("Version", equalTo(1))
            .body("Description", equalTo("resolved by arn"))
            .body("LicenseInfo", equalTo("Apache-2.0"))
            .body("CompatibleRuntimes", hasItem("python3.12"))
            .body("CompatibleArchitectures", hasItem("x86_64"))
            .body("Content.CodeSha256", notNullValue())
            .body("Content.CodeSize", notNullValue())
            .body("Content.Location", containsString("/layers/"));
    }

    @Test
    @Order(2)
    void matchesGetLayerVersionForTheSameVersion() throws Exception {
        String arn = publishLayer();
        long version = Long.parseLong(arn.substring(arn.lastIndexOf(':') + 1));

        String byName = given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions/" + version)
        .then()
            .statusCode(200)
            .extract().path("Content.CodeSha256");

        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", arn)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Content.CodeSha256", equalTo(byName));
    }

    @Test
    @Order(3)
    void wellFormedButAbsentArnIsResourceNotFound() {
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", "arn:aws:lambda:us-east-1:000000000000:layer:no-such-layer:9")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("The resource you requested does not exist."));
    }

    @Test
    @Order(4)
    void malformedArnIsValidationException() {
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", "not-an-arn")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("failed to satisfy constraint"));
    }

    @Test
    @Order(5)
    void arnWithoutAVersionIsValidationException() {
        // A layer ARN, not a layer *version* ARN: the pattern requires the trailing version.
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", "arn:aws:lambda:us-east-1:000000000000:layer:" + LAYER_NAME)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void findWithoutArnIsValidationException() {
        given()
            .queryParam("find", "LayerVersion")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("Member must not be null"));
    }

    @Test
    @Order(7)
    void awsManagedLayerArnFormPassesValidation() {
        // arn:<partition>:lambda:::awslayer:<name> is the second alternative in the pattern the
        // live service enforces. No such layer can exist here, so it resolves to 404, not 400.
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", "arn:aws:lambda:::awslayer:AmazonLinux1803")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(7)
    void arnNamingAnotherAccountDoesNotResolveTheCallersOwnLayer() throws Exception {
        String arn = publishLayer();
        // Same region, name and version — only the account differs. The lookup is keyed within
        // the caller's own partition, so without an account check this would return the layer
        // above under someone else's ARN.
        String foreign = arn.replaceFirst(":\\d{12}:", ":111111111111:");

        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", foreign)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        // control: the identical ARN under the caller's own account still resolves
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", arn)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("LayerVersionArn", equalTo(arn));
    }

    @Test
    @Order(7)
    void arnInAnotherPartitionIsRejected() throws Exception {
        String arn = publishLayer();
        // Same account, region, name and version - only the partition differs. Measured against
        // the live service, which answers InvalidParameterValueException rather than resolving.
        for (String partition : new String[] {"aws-cn", "aws-us-gov", "aws-iso"}) {
            given()
                .queryParam("find", "LayerVersion")
                .queryParam("Arn", arn.replaceFirst("^arn:aws:", "arn:" + partition + ":"))
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("Invalid layer version arn:" + partition + ":"));
        }

        // control: the same ARN in the aws partition still resolves
        given()
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", arn)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("LayerVersionArn", equalTo(arn));
    }

    @Test
    @Order(8)
    void listLayersStillWorksWithoutFind() throws Exception {
        publishLayer();

        given()
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers", notNullValue())
            .body("Layers.LayerName", hasItem(LAYER_NAME));
    }

    @Test
    @Order(9)
    void unknownFindValueFallsThroughToListLayers() throws Exception {
        publishLayer();

        // Measured against the live service: an unrecognised `find` is ignored and the request
        // is answered as ListLayers, rather than rejected.
        given()
            .queryParam("find", "SomethingElse")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers", notNullValue());
    }

    @Test
    @Order(10)
    void findMatchIsCaseSensitive() {
        // Also measured: only the exact string dispatches; other casings are ListLayers calls,
        // which is why an Arn alongside them is simply ignored rather than resolved.
        for (String find : new String[] {"layerversion", "LAYERVERSION", "LayerversioN"}) {
            given()
                .queryParam("find", find)
                .queryParam("Arn", "arn:aws:lambda:us-east-1:000000000000:layer:" + LAYER_NAME + ":1")
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(200)
                .body("Layers", notNullValue());
        }
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void cleanup_deletePublishedVersions() {
        List<Integer> versions = given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(200)
            .extract().path("LayerVersions.Version");

        for (Integer version : versions) {
            given()
            .when()
                .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/" + version)
            .then()
                .statusCode(204);
        }
    }
}
