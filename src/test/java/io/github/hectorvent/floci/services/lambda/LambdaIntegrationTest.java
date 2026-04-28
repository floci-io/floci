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
class LambdaIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";

    @Test
    @Order(1)
    void createFunction() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "hello-world",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Timeout": 30,
                    "MemorySize": 256,
                    "Description": "Integration test function"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo("hello-world"))
            .body("Runtime", equalTo("nodejs20.x"))
            .body("Handler", equalTo("index.handler"))
            .body("Timeout", equalTo(30))
            .body("MemorySize", equalTo(256))
            .body("State", equalTo("Active"))
            .body("FunctionArn", containsString("hello-world"))
            .body("RevisionId", notNullValue())
            .body("Version", equalTo("$LATEST"));
    }

    @Test
    @Order(2)
    void createFunctionDuplicate_returns409() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "hello-world",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(3)
    void getFunction() {
        given()
        .when()
            .get(BASE_PATH + "/functions/hello-world")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("hello-world"))
            .body("Configuration.State", equalTo("Active"))
            .body("Code.RepositoryType", equalTo("S3"));
    }

    @Test
    @Order(4)
    void getFunction_notFound_returns404() {
        given()
        .when()
            .get(BASE_PATH + "/functions/nonexistent-function")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(5)
    void listFunctions() {
        given()
        .when()
            .get(BASE_PATH + "/functions")
        .then()
            .statusCode(200)
            .body("Functions", notNullValue())
            .body("Functions.size()", greaterThanOrEqualTo(1))
            .body("Functions.FunctionName", hasItem("hello-world"));
    }

    @Test
    @Order(6)
    void invokeDryRun() {
        given()
            .header("X-Amz-Invocation-Type", "DryRun")
            .contentType("application/json")
            .body("{\"key\": \"value\"}")
        .when()
            .post(BASE_PATH + "/functions/hello-world/invocations")
        .then()
            .statusCode(204)
            .header("X-Amz-Executed-Version", equalTo("$LATEST"))
            .header("X-Amz-Request-Id", notNullValue());
    }

    @Test
    @Order(7)
    void invokeNotFoundFunction_returns404() {
        given()
            .header("X-Amz-Invocation-Type", "DryRun")
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE_PATH + "/functions/no-such-function/invocations")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(8)
    void createFunctionMissingRole_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "bad-fn",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400);
    }

    // ── Issue #439: LastUpdateStatus in responses ─────────────────────

    @Test
    @Order(9)
    void getFunctionIncludesLastUpdateStatus() {
        given()
        .when()
            .get(BASE_PATH + "/functions/hello-world")
        .then()
            .statusCode(200)
            .body("Configuration.LastUpdateStatus", equalTo("Successful"));
    }

    @Test
    @Order(10)
    void updateFunctionConfiguration() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Timeout": 60,
                    "MemorySize": 512,
                    "Description": "Updated description",
                    "Environment": {
                        "Variables": {
                            "MY_KEY": "my-value",
                            "ANOTHER_KEY": "another-value"
                        }
                    }
                }
                """)
        .when()
            .put(BASE_PATH + "/functions/hello-world/configuration")
        .then()
            .statusCode(200)
            .body("FunctionName", equalTo("hello-world"))
            .body("Timeout", equalTo(60))
            .body("MemorySize", equalTo(512))
            .body("Description", equalTo("Updated description"))
            .body("Environment.Variables.MY_KEY", equalTo("my-value"))
            .body("Environment.Variables.ANOTHER_KEY", equalTo("another-value"))
            .body("RevisionId", notNullValue());
    }

    @Test
    @Order(11)
    void updateFunctionConfiguration_notFound_returns404() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Timeout": 30
                }
                """)
        .when()
            .put(BASE_PATH + "/functions/nonexistent-function/configuration")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(12)
    void deleteFunction() {
        given()
        .when()
            .delete(BASE_PATH + "/functions/hello-world")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(13)
    void deletedFunctionNotFound() {
        given()
        .when()
            .get(BASE_PATH + "/functions/hello-world")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(14)
    void createFunctionWithLargeInlineZip() throws Exception {
        // Build a valid zip with a handler file + 16 MB padding so the base64
        // encoding exceeds Jackson's former 20 MB maxStringLength default.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write("def handler(event, context): return 'ok'".getBytes());
            zos.closeEntry();

            // 16 MB padding file using incompressible data so the zip (and its
            // base64 encoding) actually exceeds Jackson's former 20 MB limit
            zos.putNextEntry(new ZipEntry("padding.bin"));
            byte[] chunk = new byte[1024 * 1024];
            java.util.Random rng = new java.util.Random(42);
            for (int i = 0; i < 16; i++) {
                rng.nextBytes(chunk);
                zos.write(chunk);
            }
            zos.closeEntry();
        }
        String base64Zip = Base64.getEncoder().encodeToString(baos.toByteArray());

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "large-zip-fn",
                    "Runtime": "python3.10",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "handler.handler",
                    "Code": {
                        "ZipFile": "%s"
                    }
                }
                """.formatted(base64Zip))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo("large-zip-fn"));

        // cleanup
        given().delete(BASE_PATH + "/functions/large-zip-fn");
    }

    // ── ImageConfig ───────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createImageFunctionWithImageConfig() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "image-fn",
                    "PackageType": "Image",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Code": {
                        "ImageUri": "123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo:latest"
                    },
                    "ImageConfig": {
                        "Command": ["app.handler"],
                        "EntryPoint": ["/lambda-entrypoint.sh"]
                    }
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo("image-fn"))
            .body("PackageType", equalTo("Image"))
            .body("ImageConfigResponse.ImageConfig.Command", hasItem("app.handler"))
            .body("ImageConfigResponse.ImageConfig.EntryPoint", hasItem("/lambda-entrypoint.sh"));
    }

    @Test
    @Order(21)
    void getFunctionReturnsImageConfig() {
        given()
        .when()
            .get(BASE_PATH + "/functions/image-fn")
        .then()
            .statusCode(200)
            .body("Configuration.ImageConfigResponse.ImageConfig.Command",
                    hasItem("app.handler"))
            .body("Configuration.ImageConfigResponse.ImageConfig.EntryPoint",
                    hasItem("/lambda-entrypoint.sh"));
    }

    @Test
    @Order(22)
    void updateImageFunctionConfig() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ImageConfig": {
                        "Command": ["new.handler"]
                    }
                }
                """)
        .when()
            .put(BASE_PATH + "/functions/image-fn/configuration")
        .then()
            .statusCode(200)
            .body("ImageConfigResponse.ImageConfig.Command", hasItem("new.handler"));
    }

    @Test
    @Order(23)
    void deleteImageFunction() {
        given()
        .when()
            .delete(BASE_PATH + "/functions/image-fn")
        .then()
            .statusCode(204);
    }
}
