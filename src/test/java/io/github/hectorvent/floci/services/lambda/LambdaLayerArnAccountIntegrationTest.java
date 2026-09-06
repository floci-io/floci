package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Layer version ARNs carry an account, and resolution has to honour it.
 *
 * <p>Measured against the live service in ap-southeast-1. Attaching an AWS-managed public layer
 * succeeds and the ARN is echoed verbatim; an ARN in another account whose layer name and version
 * match one of the caller's own is {@code AccessDeniedException}, never a substitution; and only
 * a missing layer in the caller's own account is
 * {@code InvalidParameterValueException: Layer version ... does not exist.}
 *
 * <p>Floci implements no layer permissions, so it cannot reproduce the AccessDenied case and
 * accepts a foreign ARN unresolved instead. What it must not do is resolve that ARN to a
 * same-named layer of the caller's own.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaLayerArnAccountIntegrationTest {

    private static final String LAYER_NAME = "arn-account-layer";
    private static final String LOCAL_ACCOUNT = "000000000000";
    private static final String FOREIGN_ACCOUNT = "017000801446";
    private static final String POWERTOOLS_ARN =
            "arn:aws:lambda:us-east-1:017000801446:layer:AWSLambdaPowertoolsPythonV3-python313-arm64:36";

    @Inject
    LambdaLayerService layerService;

    private static String zipBase64(String path, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes());
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
                    "CompatibleRuntimes": ["python3.12"]
                }
                """.formatted(zipBase64("python/shared.py", "VALUE = 1")))
        .when()
            .post("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(201)
            .extract().path("LayerVersionArn");
    }

    private static void createFunction(String name, String layerArn, int expectedStatus) throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/r",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" },
                    "Layers": ["%s"]
                }
                """.formatted(name, zipBase64("handler.py", "def handler(e, c): return {}"), layerArn))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(expectedStatus);
    }

    @Test
    @Order(1)
    void foreignAccountArnDoesNotResolveToTheCallersOwnSameNamedLayer() throws Exception {
        String ownArn = publishLayer();
        assertNotNull(layerService.resolveLayerByArn(ownArn),
                "the caller's own layer must still resolve by its own ARN");

        String foreignArn = ownArn.replace(":" + LOCAL_ACCOUNT + ":", ":" + FOREIGN_ACCOUNT + ":");
        assertNull(layerService.resolveLayerByArn(foreignArn),
                "an ARN naming another account must not resolve to the caller's same-named layer");
    }

    @Test
    @Order(2)
    void foreignPartitionArnDoesNotResolveToTheLocalLayer() throws Exception {
        String ownArn = publishLayer();

        assertNull(layerService.resolveLayerByArn(ownArn.replace("arn:aws:", "arn:aws-cn:")),
                "an ARN in another partition must not resolve to the local layer");
    }

    @Test
    @Order(2)
    void foreignPartitionArnIsRejectedWhenAttachedToAFunction() throws Exception {
        // GetLayerVersionByArn already calls a non-aws partition invalid. Attaching one has to
        // agree, or a function persists an ARN Floci's own lookup path rejects. A foreign account
        // is different: a resource policy can make that layer readable, so it stays accepted.
        createFunction("arn-account-foreign-partition",
                "arn:aws-cn:lambda:cn-north-1:123456789012:layer:probe:1", 400);

        given()
        .when()
            .get("/2015-03-31/functions/arn-account-foreign-partition")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(3)
    void awsManagedLayerArnIsAcceptedAndEchoedVerbatim() throws Exception {
        createFunction("arn-account-managed", POWERTOOLS_ARN, 201);

        given()
        .when()
            .get("/2015-03-31/functions/arn-account-managed")
        .then()
            .statusCode(200)
            .body("Configuration.Layers", hasSize(1))
            .body("Configuration.Layers[0].Arn", equalTo(POWERTOOLS_ARN));

        given()
        .when()
            .delete("/2015-03-31/functions/arn-account-managed")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(4)
    void missingLayerInTheCallersOwnAccountIsStillRejected() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "arn-account-typo",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/r",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" },
                    "Layers": ["arn:aws:lambda:us-east-1:000000000000:layer:no-such-layer:1"]
                }
                """.formatted(zipBase64("handler.py", "def handler(e, c): return {}")))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo(
                "Layer version arn:aws:lambda:us-east-1:000000000000:layer:no-such-layer:1"
                        + " does not exist."));
    }

    @Test
    @Order(5)
    void updateFunctionConfigurationAcceptsAForeignAccountArn() throws Exception {
        String ownArn = publishLayer();
        createFunction("arn-account-update", ownArn, 201);

        given()
            .contentType("application/json")
            .body("""
                {
                    "Layers": ["%s"]
                }
                """.formatted(POWERTOOLS_ARN))
        .when()
            .put("/2015-03-31/functions/arn-account-update/configuration")
        .then()
            .statusCode(200)
            .body("Layers", hasSize(1))
            .body("Layers[0].Arn", equalTo(POWERTOOLS_ARN));

        given()
        .when()
            .delete("/2015-03-31/functions/arn-account-update")
        .then()
            .statusCode(204);
    }

    // Cleanup: every version this class published, so a sibling class sees no leftovers.
    @Test
    @Order(20)
    void cleanup_deleteLayerVersions() {
        for (int version = 1; version <= 3; version++) {
            given()
            .when()
                .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/" + version)
            .then()
                .statusCode(204);
        }

        given()
        .when()
            .get("/2018-10-31/layers/" + LAYER_NAME + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", hasSize(0));
    }
}
