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
 * <p>Measured against the live service in ap-southeast-1. An ARN in another account whose layer
 * name and version match one of the caller's own is {@code AccessDeniedException}, never a
 * substitution; a cross-partition ARN is the same {@code AccessDeniedException}; and only a
 * missing layer in the caller's own account is
 * {@code InvalidParameterValueException: Layer version ... does not exist.}
 *
 * <p>This class covers the default configuration, which answers every foreign ARN the way AWS
 * answers a non-public one. {@code floci.services.lambda.accept-external-layer-arns} relaxes that
 * for same-partition ARNs, covered by {@link LambdaExternalLayerArnAcceptedIntegrationTest}.
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
        // The live probe returns AccessDeniedException for a cross-partition ARN, identical to
        // the foreign-account case, so this reports what was measured rather than reusing
        // GetLayerVersionByArn's InvalidParameterValueException. Nothing is persisted either way.
        String arn = "arn:aws-cn:lambda:cn-north-1:123456789012:layer:probe:1";
        createFunction("arn-account-foreign-partition", arn, 403);

        given()
        .when()
            .get("/2015-03-31/functions/arn-account-foreign-partition")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(3)
    void foreignAccountArnIsDeniedByDefaultAndTheFunctionIsNotCreated() throws Exception {
        // Floci cannot tell a public layer from a private one without a layer permission model,
        // so the default gives the answer AWS gives to everything but a public layer.
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "arn-account-managed",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/r",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" },
                    "Layers": ["%s"]
                }
                """.formatted(zipBase64("handler.py", "def handler(e, c): return {}"), POWERTOOLS_ARN))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"))
            .body("message", equalTo(
                "User is not authorized to perform: lambda:GetLayerVersion on resource: "
                        + POWERTOOLS_ARN + " because no resource-based policy allows the"
                        + " lambda:GetLayerVersion action"));

        given()
        .when()
            .get("/2015-03-31/functions/arn-account-managed")
        .then()
            .statusCode(404);
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
    void updateFunctionConfigurationIsDeniedAndLeavesTheExistingLayerInPlace() throws Exception {
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
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));

        // A refused update must not have partially applied: the original layer is still attached.
        given()
        .when()
            .get("/2015-03-31/functions/arn-account-update")
        .then()
            .statusCode(200)
            .body("Configuration.Layers", hasSize(1))
            .body("Configuration.Layers[0].Arn", equalTo(ownArn));

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
