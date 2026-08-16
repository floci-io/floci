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

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaVersionIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String FUNCTION_NAME = "versioned-function";

    @Test
    @Order(1)
    void createFunction() {
        given()
            .contentType("application/json")
            .body(String.format("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Description": "Version test function"
                }
                """, FUNCTION_NAME))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("$LATEST"));
    }

    @Test
    @Order(2)
    void publishVersion() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Description": "First version"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("1"))
            .body("Description", equalTo("First version"))
            .body("FunctionArn", containsString(FUNCTION_NAME + ":1"));
    }

    @Test
    @Order(3)
    void publishSecondVersion() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Description": "Second version"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("2"))
            .body("Description", equalTo("Second version"))
            .body("FunctionArn", containsString(FUNCTION_NAME + ":2"));
    }

    @Test
    @Order(4)
    void invokePublishedVersionReturnsExecutedVersion() {
        given()
            .header("X-Amz-Invocation-Type", "DryRun")
        .when()
            .post(BASE_PATH + "/functions/" + FUNCTION_NAME + ":1/invocations")
        .then()
            .statusCode(204)
            .header("X-Amz-Executed-Version", equalTo("1"));
    }

    @Test
    @Order(5)
    void listVersionsByFunction() {
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(200)
            .body("Versions", hasSize(3)) // $LATEST, 1, 2
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2"))
            .body("Versions.find { it.Version == '1' }.Description", equalTo("First version"))
            .body("Versions.find { it.Version == '2' }.Description", equalTo("Second version"));
    }

    @Test
    @Order(6)
    void deleteFunctionDeletesAllVersions() {
        // Delete function
        given()
        .when()
            .delete(BASE_PATH + "/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(204);

        // Verify it's gone
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(404);
            
        // Verify versions are gone (actually listVersionsByFunction should return 404 if function doesn't exist)
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(7)
    void invokeLatest_baselineRunsTheCode() throws Exception {
        // Baseline for the case below. Every other test in this file uses a function with no code
        // at all, so none of them can invoke; this pins that the zip, runtime and harness are
        // sound, which is what makes a failure in the next test attributable to versioning.
        // Deliberately a SEPARATE function: invoking $LATEST on the function under test would
        // leave a warm container that the version invoke then reuses.
        String fnName = "version-invoke-baseline";
        createInvocableFunction(fnName);

        try {
            given()
                .contentType("application/json")
                .body("{}")
            .when()
                .post(BASE_PATH + "/functions/" + fnName + "/invocations")
            .then()
                .statusCode(200)
                .header("X-Amz-Function-Error", nullValue())
                .body("ok", equalTo(true));
        } finally {
            given().delete(BASE_PATH + "/functions/" + fnName);
        }
    }

    @Test
    @Order(8)
    void invokePublishedVersion_runsTheVersionsCode() throws Exception {
        // A published version is only useful if it can be invoked, and nothing in this file
        // exercised that. publishVersion copies 14 fields onto the snapshot but no code location
        // (codeLocalPath, s3Bucket/s3Key, imageUri are all dropped), and ContainerLauncher.launch
        // only validates the code path when it is non-null — so a null one skips validation, the
        // container starts with nothing to run, and the invoke times out rather than throwing.
        String fnName = "version-invoke-cold";
        createInvocableFunction(fnName);

        try {
            given()
                .contentType("application/json")
                .body("{\"Description\": \"invocable\"}")
            .when()
                .post(BASE_PATH + "/functions/" + fnName + "/versions")
            .then()
                .statusCode(201)
                .body("Version", equalTo("1"));

            // The version invoke must be a COLD start to mean anything. $LATEST is never invoked
            // on this function: the warm pool is keyed by function name alone (#1988), so a warm
            // container from a $LATEST invoke gets handed to a version-qualified one and serves
            // $LATEST's code — which makes this assertion pass while the version path is broken.
            given()
                .contentType("application/json")
                .body("{}")
            .when()
                .post(BASE_PATH + "/functions/" + fnName + ":1/invocations")
            .then()
                .statusCode(200)
                // Body first: the header assertion below fails fast and would otherwise mask what
                // the version actually returned.
                .body("ok", equalTo(true))
                .header("X-Amz-Function-Error", nullValue());
        } finally {
            given().delete(BASE_PATH + "/functions/" + fnName);
        }
    }

    @Test
    @Order(9)
    void publishVersion_copiesObservabilityAndEncryptionConfig() {
        // A published version is an immutable snapshot of configuration too, not only code.
        // TracingConfig, DeadLetterConfig and KMSKeyArn are all surfaced wherever a function's
        // configuration is serialized (LambdaController:625-637) but were dropped by the
        // hand-enumerated field copy the same way the code location was, before that copy was
        // extended to include them.
        //
        // Verified through ListVersionsByFunction rather than GetFunction/GetFunctionConfiguration
        // with a :1 qualifier: those two both resolve through LambdaService.getFunction(region,
        // functionName), which hardcodes "$LATEST" in its storage lookup and never reads the
        // qualifier at all (LambdaFunctionStore.get(region, functionName) -> get(..., "$LATEST")),
        // so they cannot distinguish version 1's stored config from $LATEST's. That is a separate,
        // pre-existing gap, tracked apart from this fix. ListVersionsByFunction is genuinely
        // version-aware: it serializes each stored version object directly.
        String fnName = "version-config-snapshot";
        given()
            .contentType("application/json")
            .body(String.format("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "TracingConfig": {"Mode": "Active"},
                    "DeadLetterConfig": {"TargetArn": "arn:aws:sqs:us-east-1:000000000000:dlq"},
                    "KMSKeyArn": "arn:aws:kms:us-east-1:000000000000:key/test-key"
                }
                """, fnName))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);

        try {
            given()
                .contentType("application/json")
                .body("{}")
            .when()
                .post(BASE_PATH + "/functions/" + fnName + "/versions")
            .then()
                .statusCode(201)
                .body("Version", equalTo("1"));

            given()
            .when()
                .get(BASE_PATH + "/functions/" + fnName + "/versions")
            .then()
                .statusCode(200)
                .body("Versions.find { it.Version == '1' }.TracingConfig.Mode", equalTo("Active"))
                .body("Versions.find { it.Version == '1' }.DeadLetterConfig.TargetArn",
                        equalTo("arn:aws:sqs:us-east-1:000000000000:dlq"))
                .body("Versions.find { it.Version == '1' }.KMSKeyArn",
                        equalTo("arn:aws:kms:us-east-1:000000000000:key/test-key"));
        } finally {
            given().delete(BASE_PATH + "/functions/" + fnName);
        }
    }

    private void createInvocableFunction(String fnName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write("exports.handler = async () => ({ ok: true });".getBytes());
            zos.closeEntry();
        }
        String base64Zip = Base64.getEncoder().encodeToString(baos.toByteArray());

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Timeout": 5,
                    "Code": {
                        "ZipFile": "%s"
                    }
                }
                """.formatted(fnName, base64Zip))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);
    }
}
