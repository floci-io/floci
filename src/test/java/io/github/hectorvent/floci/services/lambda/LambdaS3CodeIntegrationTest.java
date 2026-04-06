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
 * Verifies that Lambda CreateFunction and UpdateFunctionCode accept
 * Code.S3Bucket + Code.S3Key as an alternative to an inline ZipFile.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaS3CodeIntegrationTest {

    private static final String BUCKET = "lambda-code-bucket";
    private static final String KEY    = "functions/hello-s3.zip";
    private static final String KEY_V2 = "functions/hello-s3-v2.zip";
    private static final String FN     = "hello-s3";

    // ── helpers ───────────────────────────────────────────────────────────────

    private static byte[] makeZip(String handlerJs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write(handlerJs.getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createS3Bucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void uploadCodeZipToS3() throws Exception {
        byte[] zip = makeZip("exports.handler = async (e) => ({ statusCode: 200, body: 's3-v1' });");
        given()
            .contentType("application/octet-stream")
            .body(zip)
        .when()
            .put("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);
    }

    // ── CreateFunction with S3 code ───────────────────────────────────────────

    @Test
    @Order(3)
    void createFunctionFromS3Code() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "%s"
                    }
                }
                """.formatted(FN, BUCKET, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FN))
            .body("State", equalTo("Active"))
            .body("CodeSize", greaterThan(0));
    }

    @Test
    @Order(4)
    void getFunctionShowsCodeSize() {
        given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(FN))
            .body("Configuration.CodeSize", greaterThan(0));
    }

    // ── UpdateFunctionCode with S3 code ───────────────────────────────────────

    @Test
    @Order(5)
    void uploadUpdatedCodeZipToS3() throws Exception {
        byte[] zip = makeZip("exports.handler = async (e) => ({ statusCode: 200, body: 's3-v2' });");
        given()
            .contentType("application/octet-stream")
            .body(zip)
        .when()
            .put("/" + BUCKET + "/" + KEY_V2)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void updateFunctionCodeFromS3() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "S3Bucket": "%s",
                    "S3Key": "%s"
                }
                """.formatted(BUCKET, KEY_V2))
        .when()
            .put("/2015-03-31/functions/" + FN + "/code")
        .then()
            .statusCode(200)
            .body("FunctionName", equalTo(FN))
            .body("CodeSize", greaterThan(0));
    }

    // ── Error: S3 object not found ─────────────────────────────────────────────

    @Test
    @Order(7)
    void createFunctionFromMissingS3Object_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "fn-missing-s3",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "does-not-exist.zip"
                    }
                }
                """.formatted(BUCKET))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(400);
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void deleteFunction() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(204);
    }
}
