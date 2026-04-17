package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that updating a ZIP file in S3 automatically patches
 * running Lambda containers linked to that bucket/key.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaReactiveSyncIntegrationTest {

    private static final String BUCKET = "reactive-sync-bucket";
    private static final String KEY    = "function.zip";
    private static final String FN     = "reactive-sync-fn";

    private static byte[] makeZip(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write(("exports.handler = async (e) => ({ body: '" + body + "' });").getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @Order(1)
    void setupAndInitialInvoke() throws Exception {
        // 1. Create bucket and upload V1
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body(makeZip("v1")).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);

        // 2. Create function
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/role",
                    "Handler": "index.handler",
                    "Timeout": 30,
                    "Code": {
                        "S3Bucket": "%s",
                        "S3Key": "%s"
                    }
                }
                """.formatted(FN, BUCKET, KEY))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        // 3. First invoke (starts container)
        given()
            .body("{}")
        .when()
            .post("/2015-03-31/functions/" + FN + "/invocations")
        .then()
            .statusCode(200)
            .body(containsString("v1"));
    }

    @Test
    @Order(2)
    void uploadV2AndVerifyAutoSync() throws Exception {
        // 1. Upload V2 to same bucket/key
        given().body(makeZip("v2")).when().put("/" + BUCKET + "/" + KEY).then().statusCode(200);

        // 2. Wait a bit for async event processing and Docker copy
        Thread.sleep(5000);

        // 3. Invoke again. Should see V2 without calling UpdateFunctionCode
        given()
            .body("{}")
        .when()
            .post("/2015-03-31/functions/" + FN + "/invocations")
        .then()
            .statusCode(200)
            .body(containsString("v2"));
    }

    @Test
    @Order(3)
    void cleanup() {
        given().when().delete("/2015-03-31/functions/" + FN).then().statusCode(204);
    }
}
