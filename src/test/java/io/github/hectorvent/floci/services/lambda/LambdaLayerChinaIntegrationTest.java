package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * The positive twin of the cross-partition layer cases: a layer published under a cn-north-1
 * scope carries an {@code arn:aws-cn:} ARN, and that ARN resolves for a caller in the same
 * partition, both through GetLayerVersionByArn and when a function attaches it.
 */
@QuarkusTest
class LambdaLayerChinaIntegrationTest {

    private static final String REGION = "cn-north-1";
    private static final String LAYER_NAME = "china-positive-layer";
    private static final String FUNCTION_NAME = "china-positive-layer-fn";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static String auth() {
        return PartitionMatrix.sigV4Auth(REGION, "lambda");
    }

    private static String zipBase64(String path, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    void aLayerPublishedInChinaResolvesByItsChinaArn() throws Exception {
        String layerVersionArn = given()
            .header("Authorization", auth())
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
            .body("LayerVersionArn", startsWith("arn:aws-cn:lambda:cn-north-1:000000000000:layer:" + LAYER_NAME + ":"))
            .extract().path("LayerVersionArn");
        String version = layerVersionArn.substring(layerVersionArn.lastIndexOf(':') + 1);
        cleanup.register(() -> given()
            .header("Authorization", auth())
        .when()
            .delete("/2018-10-31/layers/" + LAYER_NAME + "/versions/" + version)
        .then()
            .statusCode(204));

        given()
            .header("Authorization", auth())
            .queryParam("find", "LayerVersion")
            .queryParam("Arn", layerVersionArn)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("LayerVersionArn", equalTo(layerVersionArn));

        given()
            .header("Authorization", auth())
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "arn:aws-cn:iam::000000000000:role/r",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" },
                    "Layers": ["%s"]
                }
                """.formatted(FUNCTION_NAME, zipBase64("handler.py", "def handler(e, c): return {}"), layerVersionArn))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("Layers[0].Arn", equalTo(layerVersionArn));
        cleanup.register(() -> given()
            .header("Authorization", auth())
        .when()
            .delete("/2015-03-31/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(204));
    }
}
