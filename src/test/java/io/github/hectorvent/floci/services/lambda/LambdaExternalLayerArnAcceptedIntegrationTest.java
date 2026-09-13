package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code floci.services.lambda.accept-external-layer-arns} turned on.
 *
 * <p>The default refuses every foreign-account layer ARN, because Floci has no layer permission
 * model and so cannot tell an AWS-managed public layer from a private one that AWS would refuse.
 * A stack attaching Powertools, the AppConfig extension or a vendor-published layer needs the
 * permissive answer, which is what this flag selects: the ARN is recorded verbatim and its
 * content is never mounted.
 *
 * <p>Cross-partition stays refused even here. Partitions are isolated, so no resource policy can
 * make an {@code aws-cn} layer readable from {@code aws}, and {@code GetLayerVersionByArn} calls
 * such an ARN invalid outright.
 */
@QuarkusTest
@TestProfile(LambdaExternalLayerArnAcceptedIntegrationTest.AcceptExternalLayerArnsProfile.class)
class LambdaExternalLayerArnAcceptedIntegrationTest {

    private static final String POWERTOOLS_ARN =
            "arn:aws:lambda:us-east-1:017000801446:layer:AWSLambdaPowertoolsPythonV3-python313-arm64:36";

    private static String zipBase64(String path, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
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
    void awsManagedLayerArnIsAcceptedAndEchoedVerbatim() throws Exception {
        createFunction("ext-layer-managed", POWERTOOLS_ARN, 201);

        given()
        .when()
            .get("/2015-03-31/functions/ext-layer-managed")
        .then()
            .statusCode(200)
            .body("Configuration.Layers", hasSize(1))
            .body("Configuration.Layers[0].Arn", equalTo(POWERTOOLS_ARN));

        given()
        .when()
            .delete("/2015-03-31/functions/ext-layer-managed")
        .then()
            .statusCode(204);
    }

    @Test
    void updateFunctionConfigurationAcceptsAForeignAccountArn() throws Exception {
        createFunction("ext-layer-update", POWERTOOLS_ARN, 201);

        String other = "arn:aws:lambda:us-east-1:017000801446:layer:AWSLambdaPowertoolsPythonV3-python313-arm64:37";
        given()
            .contentType("application/json")
            .body("""
                {
                    "Layers": ["%s"]
                }
                """.formatted(other))
        .when()
            .put("/2015-03-31/functions/ext-layer-update/configuration")
        .then()
            .statusCode(200)
            .body("Layers", hasSize(1))
            .body("Layers[0].Arn", equalTo(other));

        given()
        .when()
            .delete("/2015-03-31/functions/ext-layer-update")
        .then()
            .statusCode(204);
    }

    @Test
    void foreignPartitionArnIsStillRejected() throws Exception {
        createFunction("ext-layer-foreign-partition",
                "arn:aws-cn:lambda:cn-north-1:123456789012:layer:probe:1", 403);

        given()
        .when()
            .get("/2015-03-31/functions/ext-layer-foreign-partition")
        .then()
            .statusCode(404);
    }

    @Test
    void missingLayerInTheCallersOwnAccountIsStillRejected() throws Exception {
        // The flag only relaxes foreign ARNs. A typo in the caller's own account is still the
        // eager InvalidParameterValueException AWS gives, which is the case #2813 relies on.
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "ext-layer-typo",
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
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    public static final class AcceptExternalLayerArnsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.lambda.accept-external-layer-arns", "true",
                    "quarkus.http.test-port", "4588",
                    "floci.port", "4588",
                    "floci.base-url", "http://localhost:4588");
        }
    }
}
